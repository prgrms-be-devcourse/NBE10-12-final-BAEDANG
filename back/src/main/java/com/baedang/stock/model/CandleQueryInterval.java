package com.baedang.stock.model;

public enum CandleQueryInterval {
    ONE_MINUTE("1m"),
    ONE_DAY("1d");

    private final String value;

    CandleQueryInterval(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
