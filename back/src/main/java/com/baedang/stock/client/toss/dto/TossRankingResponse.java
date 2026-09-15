package com.baedang.stock.client.toss.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record TossRankingResponse(
        Result result
) {

    public record Result(
            List<Ranking> rankings,
            OffsetDateTime rankedAt
    ) {
    }

    public record Ranking(
            int rank,
            String symbol,
            String currency,
            Price price,
            String tradingVolume,
            String tradingAmount
    ) {
    }

    public record Price(
            String lastPrice,
            String basePrice,
            String changeRate
    ) {
    }
}
