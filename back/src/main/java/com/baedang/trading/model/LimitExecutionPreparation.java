package com.baedang.trading.model;

import com.baedang.trading.entity.OrderSide;

/** 후보 선정의 호가 버전·환율 근거. revision은 잔량 재검증용이며 우선순위 초기화 키가 아닙니다. */
public record LimitExecutionPreparation(Long stockId, OrderSide side, LimitExecutionBook book,
        OrderMarketContext context, LimitExecutionOutcome.Reason reason) {
    public LimitExecutionPreparation {
        if (reason == null && (stockId == null || stockId <= 0 || side == null || book == null || context == null)) {
            throw new IllegalArgumentException("후보 선정의 종목·방향·호가·컨텍스트가 필요합니다");
        }
        if (reason != null && (stockId != null || side != null || book != null || context != null)) {
            throw new IllegalArgumentException("보류 결과에는 체결 근거를 포함하지 않습니다");
        }
    }
    public boolean available() { return reason == null; }

    public static LimitExecutionPreparation available(Long stockId, OrderSide side, LimitExecutionBook book, OrderMarketContext context) {
        return new LimitExecutionPreparation(stockId, side, book, context, null);
    }

    public static LimitExecutionPreparation unavailable(LimitExecutionOutcome.Reason reason) {
        if (reason == null) throw new IllegalArgumentException("보류 사유가 필요합니다");
        return new LimitExecutionPreparation(null, null, null, null, reason);
    }

    public boolean samePriorityBasis(LimitExecutionPreparation other) {
        return available() && other != null && other.available()
                && stockId.equals(other.stockId) && side == other.side
                && book.version().equals(other.book.version())
                && context.executionRate().compareTo(other.context.executionRate()) == 0;
    }
}
