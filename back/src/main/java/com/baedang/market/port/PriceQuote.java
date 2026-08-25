package com.baedang.market.port;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PriceQuote (
        String symbol,
        BigDecimal lastPrice,
        OffsetDateTime quoteAt,
        String currency
) {
}
