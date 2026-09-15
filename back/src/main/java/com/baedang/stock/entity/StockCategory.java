package com.baedang.stock.entity;

import com.baedang.global.normalizer.DomainNormalizer;

public enum StockCategory {
    INDIVIDUAL, PREFERRED, ETF, ETN;

    public static StockCategory from(String securityType, Boolean isCommonShare) {
        String normalizedType = DomainNormalizer.upperCode(securityType);

        if (ETF.name().equals(normalizedType)) return ETF;
        if (ETN.name().equals(normalizedType)) return ETN;
        if (Boolean.FALSE.equals(isCommonShare)) return PREFERRED;
        return INDIVIDUAL;
    }
}
