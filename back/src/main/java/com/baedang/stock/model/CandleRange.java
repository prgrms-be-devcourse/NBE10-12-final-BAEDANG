package com.baedang.stock.model;

public enum CandleRange {
    ONE_DAY("1D"),
    ONE_MONTH("1M"),
    SIX_MONTHS("6M"),
    ONE_YEAR("1Y");

    private final String value;

    CandleRange(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
