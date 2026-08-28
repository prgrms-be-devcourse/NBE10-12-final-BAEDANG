package com.baedang.stock.dto;

import com.baedang.stock.entity.StockCategory;

import java.time.OffsetDateTime;
import java.util.List;

public record RankingResponse(
        List<Item> items,
        String nextCursor,
        boolean hasNext
) {
    public record Item(
            int rank,
            String symbol,
            String name,
            String market,
            StockCategory category,
            Boolean isDividend,
            String leverageFactor,
            String currency,
            String lastPrice,
            String prevClose,
            String changeAmount,
            String changeRate,
            String tradingAmount,
            OffsetDateTime quoteAt,
            boolean realtime
    ) {
    }
}
