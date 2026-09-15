package com.baedang.trading.model;

import java.math.BigDecimal;
import java.util.List;

/** 표시용 평균값이 아니라 원본 호가별 정산 근거를 전달합니다. */
public record LimitExecutionPlan(List<Fill> fills, BigDecimal remainingQuantity,
                                 BigDecimal remainingReservedCash, BigDecimal releasedCash,
                                 CumulativeSettlementState nextState, StopReason reason) {
    public LimitExecutionPlan { fills = List.copyOf(fills); }
    public record Level(Long levelId, BigDecimal price, BigDecimal quantity) {}
    public record Fill(Long levelId, BigDecimal price, BigDecimal quantity,
                       ExecutionAmounts amounts, BigDecimal reservedCashAfter, BigDecimal releasedCash) {}
    public enum StopReason { FILLED, PRICE_LIMIT, NO_LIQUIDITY, INSUFFICIENT_RESERVED_CASH, NON_POSITIVE_SETTLEMENT }
}
