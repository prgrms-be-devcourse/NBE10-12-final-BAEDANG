package com.baedang.trading.service;

import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.repository.OrderBookLevelRepository;
import com.baedang.orderbook.repository.OrderBookRowProjection;
import com.baedang.orderbook.service.OrderBookPricePolicy;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionPlan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class LimitExecutionBookReader {
    private final OrderBookLevelRepository levels;
    private final Duration maxAge;
    private final OrderBookPricePolicy prices;

    public LimitExecutionBookReader(OrderBookLevelRepository levels,
            @Value("${trading.quote-max-staleness-seconds}") long maxAgeSeconds, OrderBookPricePolicy prices) {
        this.levels = levels;
        this.prices = prices;
        this.maxAge = Duration.ofSeconds(maxAgeSeconds);
    }

    public Optional<LimitExecutionBook> read(Stock stock, OrderSide side, Instant now) {
        OrderBookSide bookSide = side == OrderSide.BUY ? OrderBookSide.ASK : OrderBookSide.BID;
        List<OrderBookRowProjection> snapshot = levels.findActiveSnapshotRows(stock.getStockId());
        if (!prices.validSnapshot(stock, snapshot, now)) return Optional.empty();
        OrderBookRowProjection first = snapshot.getFirst();
        if (!isFresh(stock, first.getCurrency(), first.getQuoteAt(), first.getGeneratedAt(), now)) return Optional.empty();
        List<OrderBookRowProjection> rows = snapshot.stream()
                .filter(row -> bookSide.name().equals(row.getSide())).toList();
        return Optional.of(new LimitExecutionBook(first.getBookVersion(), first.getRevision(), first.getQuoteAt(), first.getGeneratedAt(),
                rows.stream().map(row -> new LimitExecutionPlan.Level(row.getLevelId(), row.getPrice(), row.getRemainingQuantity())).toList()));
    }

    public boolean isFresh(Stock stock, String currency, Instant quoteAt, Instant generatedAt, Instant now) {
        return currency != null && currency.equals(stock.getMarketCountry().defaultCurrency())
                && currency.equals(stock.getCurrency()) && quoteAt != null && generatedAt != null
                && !quoteAt.isAfter(now) && !generatedAt.isAfter(now) && !quoteAt.isAfter(generatedAt)
                && Duration.between(quoteAt, now).compareTo(maxAge) <= 0;
    }
}
