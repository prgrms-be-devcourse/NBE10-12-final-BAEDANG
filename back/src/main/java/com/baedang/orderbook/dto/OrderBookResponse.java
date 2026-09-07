package com.baedang.orderbook.dto;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.formatter.FinancialDecimalFormatter;
import com.baedang.orderbook.repository.OrderBookRowProjection;
import com.baedang.stock.entity.Stock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record OrderBookResponse(
        String symbol,
        String marketCountry,
        Long bookVersion,
        Long revision,
        String basePrice,
        String currency,
        Instant quoteAt,
        Instant generatedAt,
        boolean virtual,
        String description,
        List<OrderBookLevelResponse> asks,
        List<OrderBookLevelResponse> bids
) {
    public static final String VIRTUAL_DESCRIPTION = "현재가 기반 가상 호가·가상 잔량";

    public static OrderBookResponse from(Stock stock, List<OrderBookRowProjection> rows) {
        if (rows == null || rows.size() != 20) {
            throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
        }

        OrderBookRowProjection header = rows.getFirst();
        Long bookVersion = header.getBookVersion();
        Long revision = header.getRevision();

        List<OrderBookLevelResponse> asks = new ArrayList<>(10);
        List<OrderBookLevelResponse> bids = new ArrayList<>(10);

        for (OrderBookRowProjection row : rows) {
            if (!bookVersion.equals(row.getBookVersion()) || !revision.equals(row.getRevision())) {
                throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
            }
            if ("ASK".equals(row.getSide())) {
                asks.add(new OrderBookLevelResponse(
                        row.getLevelDepth(),
                        FinancialDecimalFormatter.plain(row.getPrice()),
                        FinancialDecimalFormatter.plain(row.getRemainingQuantity())
                ));
            } else if ("BID".equals(row.getSide())) {
                bids.add(new OrderBookLevelResponse(
                        row.getLevelDepth(),
                        FinancialDecimalFormatter.plain(row.getPrice()),
                        FinancialDecimalFormatter.plain(row.getRemainingQuantity())
                ));
            } else {
                throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
            }
        }

        if (asks.size() != 10 || bids.size() != 10) {
            throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
        }

        asks.sort(Comparator.comparingInt(OrderBookLevelResponse::level));
        bids.sort(Comparator.comparingInt(OrderBookLevelResponse::level));

        for (int i = 0; i < 10; i++) {
            if (asks.get(i).level() != i + 1 || bids.get(i).level() != i + 1) {
                throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
            }
        }

        return new OrderBookResponse(
                stock.getSymbol(),
                stock.getMarketCountry().name(),
                bookVersion,
                revision,
                FinancialDecimalFormatter.plain(header.getBasePrice()),
                header.getCurrency(),
                header.getQuoteAt(),
                header.getGeneratedAt(),
                true,
                VIRTUAL_DESCRIPTION,
                List.copyOf(asks),
                List.copyOf(bids)
        );
    }
}
