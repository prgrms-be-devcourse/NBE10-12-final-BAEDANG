package com.baedang.stock.dto;

import com.baedang.global.formatter.FinancialDecimalFormatter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;

public record CandleResponse(
        String symbol,
        String interval,
        String range,
        String currency,
        List<Item> items
) {
    public record Item(
            OffsetDateTime at,
            String open,
            String high,
            String low,
            String close,
            String volume
    ) {
        public static Item of(
                OffsetDateTime at,
                BigDecimal open,
                BigDecimal high,
                BigDecimal low,
                BigDecimal close,
                BigDecimal volume,
                String currencyCode
        ) {
            return new Item(
                    at,
                    FinancialDecimalFormatter.currency(open, currencyCode),
                    FinancialDecimalFormatter.currency(high, currencyCode),
                    FinancialDecimalFormatter.currency(low, currencyCode),
                    FinancialDecimalFormatter.currency(close, currencyCode),
                    plain(volume));
        }
    }
}
