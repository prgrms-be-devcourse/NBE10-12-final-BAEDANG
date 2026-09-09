package com.baedang.orderbook.dto;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.formatter.FinancialDecimalFormatter;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.model.OrderBookPriceOrderValidator;
import com.baedang.orderbook.repository.OrderBookRowProjection;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;

import java.math.BigDecimal;
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
    private static final BigDecimal MIN_US_ORDER_BOOK_PRICE = new BigDecimal("0.01");

    public static OrderBookResponse from(Stock stock, List<OrderBookRowProjection> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 20) {
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
        boolean usStock = stock.getMarketCountry() == MarketCountry.US;
        boolean validBidDepth = bids.size() == 10
                || (usStock && !bids.isEmpty() && bids.size() < 10
                    && bids.getLast().getPrice().compareTo(MIN_US_ORDER_BOOK_PRICE) == 0);
        if (asks.size() != 10 || !validBidDepth
                || !hasSequentialLevels(asks)
                || !hasSequentialLevels(bids)
                || !OrderBookPriceOrderValidator.isStrict(
                        asks, OrderBookRowProjection::getPrice, OrderBookSide.ASK)
                || !OrderBookPriceOrderValidator.isStrict(
                        bids, OrderBookRowProjection::getPrice, OrderBookSide.BID)) {
            throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
        }

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

    private static boolean hasSequentialLevels(List<OrderBookRowProjection> levels) {
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).getLevelDepth() != i + 1) return false;
        }
        return true;
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
