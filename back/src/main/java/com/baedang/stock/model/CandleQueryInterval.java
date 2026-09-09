package com.baedang.stock.model;

public enum CandleQueryInterval {
    ONE_MINUTE("1m"),
    FIVE_MINUTES("5m"),
    TEN_MINUTES("10m"),
    ONE_DAY("1d"),
    ONE_WEEK("1w");

    private final String value;

    CandleQueryInterval(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
