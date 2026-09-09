package com.baedang.trading.model;

public record LimitExecutionOutcome(int executionCount, Reason reason) {
    public static LimitExecutionOutcome deferred(Reason reason) { return new LimitExecutionOutcome(0, reason); }
    public enum Reason {
        EXECUTED, INACTIVE, EXPIRED, ORDER_CHANGED, BOOK_CHANGED, LOCK_BUSY,
        MARKET_CLOSED, STATUS_UNAVAILABLE, NOT_TRADABLE, NO_BOOK, STALE_BOOK,
        CONTEXT_EXPIRED, PRICE_OR_LIQUIDITY, RESERVED_CASH, NON_POSITIVE_SETTLEMENT
    }
}
