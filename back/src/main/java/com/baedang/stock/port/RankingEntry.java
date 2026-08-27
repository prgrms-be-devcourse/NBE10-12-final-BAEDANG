package com.baedang.stock.port;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

public record RankingEntry(
        int rank,
        String symbol,
        String currency,

        BigDecimal lastPrice,
        BigDecimal basePrice,
        @Nullable BigDecimal changeRate,   // basePrice 가 0 이면 null (등락률 계산 불가)

        BigDecimal tradingVolume,
        BigDecimal tradingAmount
) {
}
