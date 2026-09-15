-- 수요 기반 현재가 수집의 EXISTS: 계좌와 관계없이 미체결 종목·만료시각으로 조회합니다.
-- now()는 인덱스 조건에 넣지 않고 실행 시 expires_at 범위로 검사합니다.
CREATE INDEX ix_order_quote_target ON trade_order (stock_id, expires_at)
    WHERE order_type = 'LIMIT' AND status IN ('PENDING', 'PARTIALLY_FILLED') AND quantity > filled_quantity;

-- 워커는 종목·방향별 가격 우선, 동일 가격은 접수 시각·ID 순으로 keyset 탐색합니다.
CREATE INDEX ix_order_execute_buy ON trade_order (stock_id, limit_price DESC, ordered_at, order_id)
    WHERE order_type = 'LIMIT' AND side = 'BUY'
      AND status IN ('PENDING', 'PARTIALLY_FILLED') AND quantity > filled_quantity;
CREATE INDEX ix_order_execute_sell ON trade_order (stock_id, limit_price ASC, ordered_at, order_id)
    WHERE order_type = 'LIMIT' AND side = 'SELL'
      AND status IN ('PENDING', 'PARTIALLY_FILLED') AND quantity > filled_quantity;
