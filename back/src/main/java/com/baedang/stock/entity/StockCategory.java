package com.baedang.stock.entity;

import java.util.Locale;

public enum StockCategory {
    INDIVIDUAL, PREFERRED, ETF, ETN;

    public static StockCategory from(String securityType, Boolean isCommonShare) {
        String normalizedType = securityType == null ? "" : securityType.trim().toUpperCase(Locale.ROOT);

        if (ETF.name().equals(normalizedType)) return ETF;
        if (ETN.name().equals(normalizedType)) return ETN;
        if (Boolean.FALSE.equals(isCommonShare)) return PREFERRED;
        return INDIVIDUAL;
    }
}
