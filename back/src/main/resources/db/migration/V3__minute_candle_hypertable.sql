--flyway:executeInTransaction=false
-- V3__minute_candle_hypertable.sql
-- 분봉 상시 적재 전환 (#128): minute_candle 하이퍼테이블화 + 5분봉·10분봉 연속 집계
--
-- !! executeInTransaction=false 는 필수입니다.
--    연속 집계(CREATE MATERIALIZED VIEW ... timescaledb.continuous) 생성은
--    트랜잭션 블록 안에서 실행할 수 없습니다. V1 과 같은 이유입니다.
--
-- !! 트랜잭션 밖이라 롤백이 없다. 중도 실패하면 앞부분만 적용된 채 남는다.
--    그래서 모든 구문을 재실행 가능하게 짰다(hypertable·뷰·정책 전부
--    if_not_exists). 복구 절차는 객체를 손으로 지우는 게 아니라:
--      1) flyway repair   — 실패로 기록된 V3 이력을 지운다
--      2) 앱 재기동       — V3 가 다시 돌며 이미 만들어진 것은 건너뛴다
--    실측 확인: 재실행 시 "already exists, skipping" NOTICE 만 남고 성공한다.
--
-- 주봉(candle_1w)은 V1 에서 이미 만들었으므로 여기서는 다루지 않습니다.

-- ────────────────────────────────────────────────────────────────────────────
--  1. minute_candle → 하이퍼테이블
--
--   상시 적재로 전환하면 연 977만 행이 된다. 청크는 1일 단위로 자른다.
--   일봉과 행 수가 200배 차이 나므로 같은 1년 청크를 쓰면
--   청크 하나가 1,000만 행이 되어 의미가 없다.
--
--   PK 가 이미 (stock_id, candle_at) 이라 표 구조는 바꿀 게 없다.
--   minute_candle 을 FK 로 참조하는 테이블도 없다.
--
--   !! 압축·보존 정책은 아직 켜지 않는다 (파일 하단 참고).
-- ────────────────────────────────────────────────────────────────────────────
SELECT create_hypertable('minute_candle', 'candle_at',
                         chunk_time_interval => INTERVAL '1 day',
                         migrate_data        => TRUE,
                         if_not_exists       => TRUE);


-- ────────────────────────────────────────────────────────────────────────────
--  2. 5분봉·10분봉 자동 파생 — 1분봉만 쌓으면 나머지는 뷰로 해결된다
--
--   !! 실시간 집계(materialized_only = false)를 일부러 켜지 않았습니다.
--      TimescaleDB 2.13 부터 기본값이 OFF 라 아무것도 안 쓰면 그대로 꺼집니다.
--      진행 중인 봉은 완성될 때까지 뷰에 나오지 않습니다. 현재가는 뷰가 아니라
--      minute_candle 을 직접 읽는 1분봉이 담당합니다.
--
--   !! start_offset 은 1개월이다(1일이 아니다).
--      스케줄러는 정규장 중 상위 100종목만 채운다. 그 밖(랭킹 밖 종목,
--      장 마감 후)은 상세 진입 시 온디맨드로 최신 200개를 적재하는데,
--      장이 닫혀 있으면 그건 "마지막 거래일"의 분봉이다.
--      워터마크보다 오래된 시각에 도착한 행은 실시간 집계로 잡히지 않으므로
--      정책이 다시 훑는 창 안에 들어와야 한다. 창 밖이면 분봉이 DB 에
--      멀쩡히 있는데도 5분봉이 영영 안 만들어진다(창은 앞으로만 미끄러진다).
--
--      최장 휴장은 2025 추석 사례로 8일이다(10/2 목 → 10/10 금).
--      1주로는 모자란다. 임시공휴일까지 감안해 1개월로 잡는다.
--      과거로 거슬러 백필하는 경로는 없으므로(온디맨드도 "최신" 200개다)
--      이 창을 뚫으려면 한 달 넘게 휴장해야 한다 — 추측이 아닌 상한이다.
--      무효화된 버킷만 증분 재계산하므로 창을 넓혀도 비용은 거의 없다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE MATERIALIZED VIEW IF NOT EXISTS candle_5m WITH (timescaledb.continuous) AS
SELECT stock_id,
       time_bucket(INTERVAL '5 minutes', candle_at) AS bucket,
       first(open_price, candle_at)                 AS open_price,
       max(high_price)                              AS high_price,
       min(low_price)                               AS low_price,
       last(close_price, candle_at)                 AS close_price,
       sum(volume)                                  AS volume
  FROM minute_candle
 GROUP BY stock_id, bucket
 WITH NO DATA;

SELECT add_continuous_aggregate_policy('candle_5m',
       start_offset      => INTERVAL '1 month',
       end_offset        => INTERVAL '1 minute',
       schedule_interval => INTERVAL '1 minute',
       if_not_exists     => TRUE);

--   10분봉도 candle_5m 이 아니라 minute_candle 에서 직접 만든다.
--   계층형 연속 집계는 갱신이 한 단계 더 밀려서 최신 봉이 늦게 붙는다.
CREATE MATERIALIZED VIEW IF NOT EXISTS candle_10m WITH (timescaledb.continuous) AS
SELECT stock_id,
       time_bucket(INTERVAL '10 minutes', candle_at) AS bucket,
       first(open_price, candle_at)                  AS open_price,
       max(high_price)                               AS high_price,
       min(low_price)                                AS low_price,
       last(close_price, candle_at)                  AS close_price,
       sum(volume)                                   AS volume
  FROM minute_candle
 GROUP BY stock_id, bucket
 WITH NO DATA;

SELECT add_continuous_aggregate_policy('candle_10m',
       start_offset      => INTERVAL '1 month',
       end_offset        => INTERVAL '1 minute',
       schedule_interval => INTERVAL '1 minute',
       if_not_exists     => TRUE);


-- ────────────────────────────────────────────────────────────────────────────
--  3. 분봉 압축·보존 — 상시 적재가 자리잡으면 켤 것
--
--   압축은 90% 이상 줄여주지만 압축된 청크는 UPSERT 가 제약된다.
--   온디맨드 적재가 과거 봉을 다시 쓰는 경로가 있어 아직 켜지 않는다.
-- ────────────────────────────────────────────────────────────────────────────
-- ALTER TABLE minute_candle SET (
--     timescaledb.compress,
--     timescaledb.compress_segmentby = 'stock_id',
--     timescaledb.compress_orderby   = 'candle_at DESC'
-- );
-- SELECT add_compression_policy('minute_candle', INTERVAL '7 days');
--
-- -- 1년 지난 분봉은 삭제 (일봉이 남아 있으므로 손실 없음)
-- SELECT add_retention_policy('minute_candle', INTERVAL '1 year');


-- ── 백필이 필요 없는 이유 ────────────────────────────────────────────────
--
--   두 뷰는 WITH NO DATA 로 만들지만 수동 새로고침은 돌리지 않아도 된다.
--   갱신 정책의 창(1개월)이 API 최대 조회 범위(1주)를 넉넉히 덮기 때문이다.
--     5m → 1D·1W,  10m → 1W   (CandleQueryPolicy)
--   뷰 생성 직후 첫 정책 실행이 1개월 창 전체를 채운다. 그보다 오래된 버킷은
--   비어 있지만 조회 경로가 없다.
--
--   !! 5분봉·10분봉에 1개월 이상 구간을 추가한다면 이 전제가 깨진다.
--      그때는 start_offset 을 늘리고 아래를 한 번 돌려 과거를 채울 것.
--        CALL refresh_continuous_aggregate('candle_5m',  NULL, NULL);
--        CALL refresh_continuous_aggregate('candle_10m', NULL, NULL);
