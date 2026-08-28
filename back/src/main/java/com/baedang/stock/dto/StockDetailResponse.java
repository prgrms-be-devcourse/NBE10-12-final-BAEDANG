package com.baedang.stock.dto;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockCategory;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record StockDetailResponse(
        String symbol,
        String name,
        String englishName,
        String market,
        MarketCountry marketCountry,
        String currency,
        String isinCode,
        StockCategory category,
        String leverageFactor,
        Boolean isDividend,
        Price price,
        Info info,
        List<Warning> warnings,
        boolean tradable,
        String tradableReason
) {

    public record Price(
            String lastPrice,
            String prevClose,
            String changeAmount,
            String changeRate,
            String upperLimit,
            String lowerLimit,
            OffsetDateTime quoteAt,
            boolean realtime
    ) {
    }

    public record Info(
            String marketCap,
            String sharesOutstanding,
            LocalDate listDate
    ) {
    }

    public record Warning(String type, String label) {
    }
}
