package com.baedang.stock.entity;

public enum FinancialPeriodType {
    ANNUAL("0"),
    QUARTERLY("1");

    private final String kisCode;

    FinancialPeriodType(String kisCode) {
        this.kisCode = kisCode;
    }

    public String kisCode() {
        return kisCode;
    }
}
