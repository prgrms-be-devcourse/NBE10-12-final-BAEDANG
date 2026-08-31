package com.baedang.market.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ExchangeRateHistoryResponse(
        List<Item> items
) {
    public record Item(OffsetDateTime rateAt, String rate) {
    }
}
