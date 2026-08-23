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
--  3. minute_candle → 2주차에 켤 것
--
--   1주차에는 온디맨드 캐시라 데이터가 얼마 없다.
--   상시 적재로 전환하면 연 977만 행이 되므로 그때 아래 주석을 푼다.
--
--   청크가 1일인 이유: 일봉과 행 수가 200배 차이 난다.
--   같은 1년 청크를 쓰면 청크 하나가 1,000만 행이 되어 의미가 없다.
-- ────────────────────────────────────────────────────────────────────────────
-- SELECT create_hypertable('minute_candle', 'candle_at',
--                          chunk_time_interval => INTERVAL '1 day',
--                          migrate_data        => TRUE,
--                          if_not_exists       => TRUE);
--
-- -- 7일 지난 청크는 컬럼 압축 (90% 이상 축소)
-- ALTER TABLE minute_candle SET (
--     timescaledb.compress,
--     timescaledb.compress_segmentby = 'stock_id',
--     timescaledb.compress_orderby   = 'candle_at DESC'
-- );
-- SELECT add_compression_policy('minute_candle', INTERVAL '7 days');
--
-- -- 1년 지난 분봉은 삭제 (일봉이 남아 있으므로 손실 없음)
-- SELECT add_retention_policy('minute_candle', INTERVAL '1 year');
--
-- -- 5분봉 자동 파생 — 1분봉만 쌓으면 나머지는 뷰로 해결된다
-- CREATE MATERIALIZED VIEW candle_5m WITH (timescaledb.continuous) AS
-- SELECT stock_id,
--        time_bucket(INTERVAL '5 minutes', candle_at) AS bucket,
--        first(open_price, candle_at)                 AS open_price,
--        max(high_price)                              AS high_price,
--        min(low_price)                               AS low_price,
--        last(close_price, candle_at)                 AS close_price,
--        sum(volume)                                  AS volume
--   FROM minute_candle
--  GROUP BY stock_id, bucket
--  WITH NO DATA;
--
-- SELECT add_continuous_aggregate_policy('candle_5m',
--        start_offset      => INTERVAL '1 day',
--        end_offset        => INTERVAL '1 minute',
--        schedule_interval => INTERVAL '1 minute');


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


-- ── 확인용 ──────────────────────────────────────────────────────────────────
-- SELECT hypertable_name, num_chunks FROM timescaledb_information.hypertables;
-- SELECT view_name, materialization_hypertable_name
--   FROM timescaledb_information.continuous_aggregates;
