-- ============================================================================
--  모의 주식 트레이딩 서비스 — TimescaleDB 설정
--
--  schema.sql 다음에 실행합니다.
--    docker compose 가 01-schema.sql → 02-timescale.sql 순서로 돌립니다.
--    수동 실행:  psql -U trading -d trading -f timescale.sql
--
--  !! 이 파일을 schema.sql 에 합치지 마세요.
--     CREATE EXTENSION timescaledb 는 트랜잭션 블록 안에서 실행할 수 없습니다.
--     (백그라운드 워커를 띄우기 때문에 다른 확장과 다릅니다)
--     schema.sql 은 BEGIN ... COMMIT 으로 감싸여 있으므로 여기 넣으면
--     스키마 생성 전체가 통째로 실패합니다. 그래서 파일을 나눴습니다.
--
--  !! 이 파일은 건너뛸 수 있습니다.
--     실행하지 않으면 daily_candle 이 일반 테이블로 남을 뿐,
--     나머지 기능은 전부 그대로 동작합니다.
--     PK 가 이미 (stock_id, trade_date) 라 나중에 언제든 전환할 수 있습니다.
--
--  !! 이미지 확인
--     postgres:18-alpine 에는 TimescaleDB 가 들어 있지 않습니다.
--     docker-compose.yml 의 image 를 timescale/timescaledb 계열로 바꾸거나,
--     이 파일을 빼고 일반 PostgreSQL 로 가세요.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS timescaledb;


-- ────────────────────────────────────────────────────────────────────────────
--  1. daily_candle → 하이퍼테이블
--
--   파티션 키는 trade_date. PK 가 이미 (stock_id, trade_date) 라
--   "하이퍼테이블은 PK 에 파티션 키를 포함해야 한다"는 요건을 그대로 만족한다.
--
--   청크는 1년 단위로 자른다.
--     100종목 × 250거래일 = 연 5만 행. 하루나 한 달 단위로 자르면
--     청크가 잘게 쪼개져 플래너 오버헤드만 늘어난다.
--
--   !! 솔직히 이 규모에서 성능상 이득은 거의 없다.
--      일반 테이블 + (stock_id, trade_date) 인덱스로도 50만 행은 순식간에 훑는다.
--      지정하는 실질적 이유는 아래 2번(연속 집계)이다.
--
--   !! 하이퍼테이블은 다른 테이블에서 FK 로 참조받을 수 없다.
--      daily_candle 을 참조하는 테이블은 없으므로 문제되지 않는다.
--      (daily_candle 이 stock 을 참조하는 방향은 허용된다)
-- ────────────────────────────────────────────────────────────────────────────
SELECT create_hypertable('daily_candle', 'trade_date',
                         chunk_time_interval => INTERVAL '1 year',
                         migrate_data        => TRUE,
                         if_not_exists       => TRUE);


-- ────────────────────────────────────────────────────────────────────────────
--  2. 주봉 — 연속 집계로 파생한다
--
--   토스 API 는 interval 로 1m 과 1d 만 준다. 주봉은 우리가 만들어야 하는데,
--   별도 테이블도 배치도 없이 뷰 하나로 끝난다. 일봉이 새로 들어올 때마다
--   정책이 알아서 갱신한다.
--
--   이것이 daily_candle 을 하이퍼테이블로 지정하는 진짜 명분이다 —
--   성능이 아니라 "파생 데이터를 코드로 관리하지 않는다" 쪽이다.
--
--   !! 실시간 집계는 켜지 않는다(5분봉 쪽 주석 참고). 주봉 차트의 마지막
--      바는 "지난주 완성분"에서 끝난다. 진행 중인 주는 나오지 않는다.
--
--   !! start_offset(3개월) 보다 오래된 구간은 정책이 훑지 않는다.
--      1w+1Y 는 52주를 요구하므로, 과거 일봉을 시딩한 뒤 한 번은 반드시
--      전체 새로고침을 돌려야 3개월 이전 주봉이 채워진다. (파일 하단 참고)
--
--   !! 액면분할로 과거 일봉이 소급 조정되면 수동 새로고침이 필요하다.
--      연속 집계는 원본 UPDATE 를 자동으로 따라가지 않는다.
--        CALL refresh_continuous_aggregate('candle_1w', NULL, NULL);
--      분할은 드물게 일어나므로 그때만 실행하면 된다.
-- ────────────────────────────────────────────────────────────────────────────
CREATE MATERIALIZED VIEW candle_1w WITH (timescaledb.continuous) AS
SELECT stock_id,
       time_bucket(INTERVAL '1 week', trade_date) AS bucket,
       first(open_price, trade_date)              AS open_price,
       max(high_price)                            AS high_price,
       min(low_price)                             AS low_price,
       last(close_price, trade_date)              AS close_price,
       sum(volume)                                AS volume
  FROM daily_candle
 GROUP BY stock_id, bucket
 WITH NO DATA;

SELECT add_continuous_aggregate_policy('candle_1w',
       start_offset      => INTERVAL '3 months',
       end_offset        => INTERVAL '1 day',
       schedule_interval => INTERVAL '1 day');


-- ────────────────────────────────────────────────────────────────────────────
--  3. minute_candle → 하이퍼테이블 + 5분봉·10분봉 연속 집계
--
--   상시 적재로 전환하면 연 977만 행이 된다. 청크는 1일 단위로 자른다.
--   일봉과 행 수가 200배 차이 나므로 같은 1년 청크를 쓰면
--   청크 하나가 1,000만 행이 되어 의미가 없다.
--
--   PK 가 이미 (stock_id, candle_at) 이라 표 구조는 바꿀 게 없다.
--   minute_candle 을 FK 로 참조하는 테이블도 없다.
--
--   !! 압축·보존 정책은 아직 켜지 않는다.
--      (7일 지난 청크 압축 / 1년 지난 분봉 삭제 — 파일 하단 참고)
-- ────────────────────────────────────────────────────────────────────────────
SELECT create_hypertable('minute_candle', 'candle_at',
                         chunk_time_interval => INTERVAL '1 day',
                         migrate_data        => TRUE,
                         if_not_exists       => TRUE);


-- 5분봉·10분봉 자동 파생 — 1분봉만 쌓으면 나머지는 뷰로 해결된다.
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
CREATE MATERIALIZED VIEW candle_5m WITH (timescaledb.continuous) AS
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
       schedule_interval => INTERVAL '1 minute');

--   10분봉도 candle_5m 이 아니라 minute_candle 에서 직접 만든다.
--   계층형 연속 집계는 갱신이 한 단계 더 밀려서 최신 봉이 늦게 붙는다.
CREATE MATERIALIZED VIEW candle_10m WITH (timescaledb.continuous) AS
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
       schedule_interval => INTERVAL '1 minute');


-- ────────────────────────────────────────────────────────────────────────────
--  4. 분봉 압축·보존 — 상시 적재가 자리잡으면 켤 것
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


-- ────────────────────────────────────────────────────────────────────────────
--  하이퍼테이블로 만들지 않는 것
--
--   exchange_rate  매시 정각 적재라 통화쌍 하나당 연 6,000행.
--                  10년을 모아도 6만 행이라 청크로 자를 이유가 없다.
--                  일간·주간 그래프는 그냥 GROUP BY 하면 되고,
--                  연속 집계를 걸 만큼 무거운 쿼리도 아니다.
--                  대리키(exchange_rate_id)를 그대로 쓸 수 있다는 이점도 있다 —
--                  하이퍼테이블이면 PK 에 파티션 키를 넣어야 해서
--                  (exchange_rate_id, rate_at) 같은 어색한 복합 PK 가 된다.
--
--   quote_snapshot 종목당 1행을 UPDATE 한다. 애초에 시계열이 아니다.
--
--   ledger_entry   append-only 이지만 시간 범위 조회가 아니라
--                  계좌 단위 조회가 대부분이다. 규모도 작다.
-- ────────────────────────────────────────────────────────────────────────────


-- ── 최초 1회 전체 새로고침 ────────────────────────────────────────────────
--
--   세 뷰 모두 WITH NO DATA 로 만들었고, 갱신 정책은 각자의 start_offset
--   안쪽만 훑는다. 과거 데이터를 시딩했다면 그 창 밖의 버킷은 비어 있으므로
--   시딩 직후 한 번은 아래를 실행해야 한다. (액면분할 소급 조정 시에도 동일)
--
--   갱신 창의 끝은 버킷 경계로 내림 정렬된다(실측 확인). 정책은 end_offset
--   때문에 항상 이 경로라 진행 중인 봉을 저장하지 않는다. 반면 아래처럼
--   NULL 로 전 구간을 돌리면 그 시점의 미완성 봉까지 저장된다 — 이후 봉이
--   채워지면 무효화 로그를 타고 다시 계산되므로 결국 맞춰진다.
--
--     CALL refresh_continuous_aggregate('candle_5m',  NULL, NULL);
--     CALL refresh_continuous_aggregate('candle_10m', NULL, NULL);
--     CALL refresh_continuous_aggregate('candle_1w',  NULL, NULL);


-- ── 확인용 ──────────────────────────────────────────────────────────────────
-- SELECT hypertable_name, num_chunks FROM timescaledb_information.hypertables;
-- SELECT view_name, materialization_hypertable_name
--   FROM timescaledb_information.continuous_aggregates;
