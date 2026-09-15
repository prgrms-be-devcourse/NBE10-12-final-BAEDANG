package com.baedang.orderbook.dto;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.formatter.FinancialDecimalFormatter;
import com.baedang.orderbook.repository.OrderBookRowProjection;
import com.baedang.orderbook.service.OrderBookPricePolicy;
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

    public static OrderBookResponse from(Stock stock, List<OrderBookRowProjection> rows, OrderBookPricePolicy prices, Instant now) {
        if (!prices.validSnapshot(stock, rows, now)) {
            throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
        }

        OrderBookRowProjection header = rows.getFirst();
        Long bookVersion = header.getBookVersion();
        Long revision = header.getRevision();

        List<OrderBookRowProjection> asks = new ArrayList<>(10);
        List<OrderBookRowProjection> bids = new ArrayList<>(10);

        for (OrderBookRowProjection row : rows) {
            if (!bookVersion.equals(row.getBookVersion()) || !revision.equals(row.getRevision())) {
                throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
            }
            if (row.getLevelId() == null) continue;
            if ("ASK".equals(row.getSide())) {
                asks.add(row);
            } else if ("BID".equals(row.getSide())) {
                bids.add(row);
            } else {
                throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
            }
        }

        asks.sort(Comparator.comparingInt(OrderBookRowProjection::getLevelDepth));
        bids.sort(Comparator.comparingInt(OrderBookRowProjection::getLevelDepth));
        return new OrderBookResponse(
                stock.getSymbol(),
                stock.getMarketCountry().name(),
                bookVersion,
                revision,
                FinancialDecimalFormatter.currency(header.getBasePrice(), header.getCurrency()),
                header.getCurrency(),
                header.getQuoteAt(),
                header.getGeneratedAt(),
                true,
                VIRTUAL_DESCRIPTION,
                toLevelResponses(asks, header.getCurrency()),
                toLevelResponses(bids, header.getCurrency())
        );
    }

    private static List<OrderBookLevelResponse> toLevelResponses(
            List<OrderBookRowProjection> rows,
            String currency
    ) {
        return rows.stream()
                .map(row -> new OrderBookLevelResponse(
                        row.getLevelDepth(),
                        FinancialDecimalFormatter.currency(row.getPrice(), currency),
                        FinancialDecimalFormatter.plain(row.getRemainingQuantity())
                ))
                .toList();
    }
}
