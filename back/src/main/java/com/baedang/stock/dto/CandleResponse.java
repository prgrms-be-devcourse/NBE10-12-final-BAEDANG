package com.baedang.stock.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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
                BigDecimal volume
        ) {
            return new Item(
                    at,
                    plain(open),
                    plain(high),
                    plain(low),
                    plain(close),
                    plain(volume));
        }

        private static String plain(BigDecimal value) {
            return value == null ? null : value.stripTrailingZeros().toPlainString();
        }
    }
}
