package com.baedang.market.client.toss.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record TossCandleResponse(
        TossCandleResult result
) {
    public record TossCandleResult(
            List<TossCandleItem> candles,
            OffsetDateTime nextBefore
    ) {
    }

    public record TossCandleItem(
            OffsetDateTime candleAt,
            String openPrice,
            String highPrice,
            String lowPrice,
            String closePrice,
            String volume,
            String currency
    ){
    }
}
