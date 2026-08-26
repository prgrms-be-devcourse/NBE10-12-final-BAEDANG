package com.baedang.market.client.toss.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record TossPriceResponse(
        List<TossPriceItem> result
) {
    public record TossPriceItem(
            String symbol,
            OffsetDateTime timestamp,
            String lastPrice,
            String currency
    ){}
}
