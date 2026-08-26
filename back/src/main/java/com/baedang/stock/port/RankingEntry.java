package com.baedang.stock.port;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

public record RankingEntry(
        int rank,
        String symbol,
        String currency,

        BigDecimal lastPrice,
        BigDecimal basePrice,
        @Nullable BigDecimal changeRate,

        BigDecimal tradingVolume,
        BigDecimal tradingAmount
) {
}
