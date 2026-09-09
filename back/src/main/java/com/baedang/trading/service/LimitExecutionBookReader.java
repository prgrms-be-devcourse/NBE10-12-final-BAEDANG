package com.baedang.trading.service;

import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.model.OrderBookPriceOrderValidator;
import com.baedang.orderbook.repository.OrderBookLevelRepository;
import com.baedang.orderbook.repository.OrderBookRowProjection;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class LimitExecutionBookReader {
    private final OrderBookLevelRepository levels;
    private final Duration maxAge;

    public LimitExecutionBookReader(OrderBookLevelRepository levels,
            @Value("${trading.quote-max-staleness-seconds}") long maxAgeSeconds) {
        this.levels = levels;
        this.maxAge = Duration.ofSeconds(maxAgeSeconds);
    }

    public Optional<LimitExecutionBook> read(Stock stock, OrderSide side, Instant now) {
        OrderBookSide bookSide = side == OrderSide.BUY ? OrderBookSide.ASK : OrderBookSide.BID;
        List<OrderBookRowProjection> rows = levels.findActiveSnapshotRows(stock.getStockId()).stream()
                .filter(row -> bookSide.name().equals(row.getSide())).toList();
        if (rows.isEmpty()) return Optional.empty();
        OrderBookRowProjection first = rows.getFirst();
        if (!isFresh(stock, first.getCurrency(), first.getQuoteAt(), first.getGeneratedAt(), now)) return Optional.empty();
        boolean validDepth = rows.size() == 10 || (bookSide == OrderBookSide.BID && "USD".equals(first.getCurrency())
                && rows.size() < 10 && rows.getLast().getPrice().compareTo(new BigDecimal("0.01")) == 0);
        if (!validDepth || !OrderBookPriceOrderValidator.isStrict(rows, OrderBookRowProjection::getPrice, bookSide)) {
            throw new IllegalStateException("미리보기 호가 깊이/순서 불일치");
        }
        for (int index = 0; index < rows.size(); index++) {
            OrderBookRowProjection row = rows.get(index);
            if (row.getLevelDepth() != index + 1 || !row.getBookVersion().equals(first.getBookVersion())
                    || !row.getRevision().equals(first.getRevision())) throw new IllegalStateException("호가 스냅샷 불일치");
        }
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
