-- V14__trade_order_market_event_rejection.sql
-- CB 거절 주문의 정확한 이벤트 감사 연결 (#166)
--
-- 왜 필요한가: 멱등 재생은 최초 판정에 사용한 이벤트를 그대로 복원해야 한다.
-- ordered_at 시점의 활성 이벤트를 재검색하면, 최초 거절 뒤 늦게 수집된 더 긴 CB가
-- 선택돼 stage/triggered_at/halt_until이 바뀔 수 있다. (Part 3은 늦게 수신한 이벤트도
-- 과거 triggered_at으로 저장한다.) 그래서 거절 주문이 사용한 행을 FK로 고정한다.

ALTER TABLE trade_order
    ADD COLUMN market_event_id BIGINT REFERENCES market_event (market_event_id);

COMMENT ON COLUMN trade_order.market_event_id IS
    'MARKET_TRADING_HALTED 판정에 사용한 market_event. 멱등 재생이 이 행으로 오류 데이터를 복원한다.';

-- CB 거절에만 필수, 다른 주문·거절에는 금지.
--
-- 상태까지 결합한다: reject_reason만 보면 PENDING/FILLED/CANCELED 행이 이 사유와 이벤트 ID를
-- 들고 있어도 통과한다. CB 거절은 REJECTED 확정 행에만 존재할 수 있다.
ALTER TABLE trade_order
    ADD CONSTRAINT ck_trade_order_market_event_rejection CHECK (
        (reject_reason IS DISTINCT FROM 'MARKET_TRADING_HALTED' AND market_event_id IS NULL)
        OR
        (status = 'REJECTED'
         AND reject_reason = 'MARKET_TRADING_HALTED'
         AND market_event_id IS NOT NULL)
    );
