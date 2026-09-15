package com.baedang.orderbook.service;

import com.baedang.market.model.TradingPriceLimits;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.orderbook.repository.OrderBookRowProjection;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** 생성·조회·체결에서 같은 가격 배열을 사용해 경계에 의한 축소와 레벨 누락을 구분합니다. */
@Component
public class OrderBookPricePolicy {
    public static final String VERSION = "V2";
    private final TickSizePolicy ticks;

    public OrderBookPricePolicy(TickSizePolicy ticks) {
        this.ticks = ticks;
    }

    public List<BigDecimal> prices(StockDescriptor stock, BigDecimal base, OrderBookSide side,
            TradingPriceLimits limits, Instant now) {
        if (!limits.usable(stock.marketCountry(), now) || !limits.contains(stock.marketCountry(), base)) {
            throw new IllegalArgumentException("당일 상하한가 또는 기준 가격이 유효하지 않습니다");
        }
        List<BigDecimal> prices = new ArrayList<>(10);
        BigDecimal price = base;
        for (int depth = 0; depth < 10; depth++) {
            Optional<BigDecimal> next = side == OrderBookSide.ASK
                    ? ticks.findNextValidPriceAbove(stock, price) : ticks.findPreviousValidPriceBelow(stock, price);
            if (next.isEmpty() || !limits.contains(stock.marketCountry(), next.get())) break;
            price = next.get();
            prices.add(price);
        }
        return List.copyOf(prices);
    }

    public boolean validSnapshot(Stock stock, List<OrderBookRowProjection> rows, Instant now) {
        if (rows == null || rows.isEmpty() || rows.size() > 20) return false;
        OrderBookRowProjection header = rows.getFirst();
        TradingPriceLimits limits = new TradingPriceLimits(header.getPriceLimitDate(), header.getLowerLimit(), header.getUpperLimit());
        if (stock.getMarketCountry() == MarketCountry.KR && !"KRW".equals(header.getLimitCurrency())) return false;
        if (header.getQuoteAt() == null || header.getGeneratedAt() == null
                || header.getQuoteAt().isAfter(now) || header.getGeneratedAt().isAfter(now)
                || header.getQuoteAt().isAfter(header.getGeneratedAt())
                || !header.getQuoteAt().atZone(stock.getMarketCountry().zoneId()).toLocalDate()
                    .equals(now.atZone(stock.getMarketCountry().zoneId()).toLocalDate())) return false;
        for (OrderBookRowProjection row : rows) {
            if (!Objects.equals(header.getBookVersion(), row.getBookVersion())
                    || !Objects.equals(header.getRevision(), row.getRevision())) return false;
            if (row.getLevelId() == null) {
                if (rows.size() != 1 || row.getSide() != null) return false;
            } else if (row.getLevelDepth() == null || row.getRemainingQuantity() == null
                    || row.getRemainingQuantity().signum() < 0
                    || !("ASK".equals(row.getSide()) || "BID".equals(row.getSide()))) return false;
        }
        for (OrderBookSide side : OrderBookSide.values()) {
            List<OrderBookRowProjection> levels = rows.stream().filter(row -> side.name().equals(row.getSide())).toList();
            if (!matches(StockDescriptor.from(stock), header.getBasePrice(), header.getPolicyVersion(), side,
                    limits, now, levels, OrderBookRowProjection::getPrice, OrderBookRowProjection::getLevelDepth)) return false;
        }
        return true;
    }

    public <T> boolean matches(StockDescriptor stock, BigDecimal base, String version, OrderBookSide side,
            TradingPriceLimits limits, Instant now, List<T> levels,
            Function<T, BigDecimal> price, ToIntFunction<T> depth) {
        if (!VERSION.equals(version)) return false;
        try {
            List<BigDecimal> expected = prices(stock, base, side, limits, now);
            if (levels.size() != expected.size()) return false;
            for (int index = 0; index < levels.size(); index++) {
                T level = levels.get(index);
                if (depth.applyAsInt(level) != index + 1 || price.apply(level) == null
                        || expected.get(index).compareTo(price.apply(level)) != 0) return false;
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
