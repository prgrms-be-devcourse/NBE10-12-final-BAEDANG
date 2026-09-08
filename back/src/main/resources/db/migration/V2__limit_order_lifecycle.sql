-- V2__limit_order_lifecycle.sql
-- 지정가 주문 생명주기 (#120): 원본 입력값·통화·접수환율 컬럼 추가 및 제약조건·인덱스 갱신

-- 1. trade_order 컬럼 추가
ALTER TABLE trade_order
    ADD COLUMN requested_limit_price NUMERIC(19,4),
    ADD COLUMN requested_limit_currency VARCHAR(3),
    ADD COLUMN acceptance_exchange_rate NUMERIC(19,6);

COMMENT ON COLUMN trade_order.requested_limit_price IS '원본 입력 단가: 멱등 비교/입력 의도 보존';
COMMENT ON COLUMN trade_order.requested_limit_currency IS '원본 입력 통화: KRW/USD';
COMMENT ON COLUMN trade_order.acceptance_exchange_rate IS '접수 시 환산 근거: 이후 체결 환율과 별개';

-- 2. 제약조건 갱신
ALTER TABLE trade_order
    DROP CONSTRAINT IF EXISTS ck_order_limit_terms;

ALTER TABLE trade_order
    ADD CONSTRAINT ck_order_limit_terms CHECK (order_type <> 'LIMIT' OR (
        limit_price IS NOT NULL AND limit_price > 0
        AND requested_limit_price IS NOT NULL AND requested_limit_price > 0
        AND requested_limit_currency IS NOT NULL AND requested_limit_currency IN ('KRW','USD')
        AND requested_limit_price = round(requested_limit_price, CASE WHEN requested_limit_currency = 'KRW' THEN 0 ELSE 2 END)
        AND acceptance_exchange_rate IS NOT NULL AND acceptance_exchange_rate > 0
        AND (status = 'REJECTED' OR (expires_at IS NOT NULL AND expires_at > ordered_at))
        AND (side <> 'BUY' OR status NOT IN ('PENDING','PARTIALLY_FILLED') OR reserved_cash > 0)));

ALTER TABLE trade_order
    DROP CONSTRAINT IF EXISTS ck_order_market_terms;

ALTER TABLE trade_order
    ADD CONSTRAINT ck_order_market_terms CHECK (order_type <> 'MARKET' OR (
        status IN ('FILLED','REJECTED') AND limit_price IS NULL AND reserved_cash = 0
        AND requested_limit_price IS NULL AND requested_limit_currency IS NULL AND acceptance_exchange_rate IS NULL));

-- 3. 계좌별 주문 ID 내림차순 커서 조회 인덱스 갱신
DROP INDEX IF EXISTS ix_order_history;
CREATE INDEX ix_order_history ON trade_order (account_id, order_id DESC);
