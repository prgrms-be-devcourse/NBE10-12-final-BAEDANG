package com.baedang.stock.dto;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockLike;

import java.util.List;

import static com.baedang.global.formatter.FinancialDecimalFormatter.currency;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;

public record StockLikePageResponse(
        List<Item> items,
        String nextCursor,
        boolean hasNext
) {

    public record Item(
            Long stockLikeId,
            Long stockId,
            String symbol,
            String name,
            String marketCountry,
            String prevClose,
            String lastPrice,
            String changeRate
    ) {

        public static Item of(StockLike like, Stock stock, QuoteSnapshot quote) {
            return new Item(
                    like.getStockLikeId(),
                    stock.getStockId(),
                    stock.getSymbol(),
                    stock.getName(),
                    stock.getMarketCountry().name(),
                    quote == null ? null : currency(quote.getPrevClose(), stock.getCurrency()),
                    quote == null ? null : currency(quote.getLastPrice(), stock.getCurrency()),
                    quote == null ? null : plain(quote.changeRate())
            );
        }
    }
}
