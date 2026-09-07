package com.baedang.orderbook.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.dto.OrderBookResponse;
import com.baedang.orderbook.repository.OrderBookLevelRepository;
import com.baedang.orderbook.repository.OrderBookRowProjection;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class OrderBookQueryService {

    private final StockRepository stockRepository;
    private final OrderBookLevelRepository levelRepository;
    private final MarketSessionProvider marketSessionProvider;
    private final OrderBookProperties properties;
    private final Clock clock;

    public OrderBookQueryService(
            StockRepository stockRepository,
            OrderBookLevelRepository levelRepository,
            MarketSessionProvider marketSessionProvider,
            OrderBookProperties properties,
            Clock clock
    ) {
        this.stockRepository = stockRepository;
        this.levelRepository = levelRepository;
        this.marketSessionProvider = marketSessionProvider;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public OrderBookResponse getOrderBook(String rawSymbol, String rawMarketCountry) {
        MarketCountry marketCountry = MarketCountry.parse(rawMarketCountry)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
        String symbol = DomainNormalizer.symbol(rawSymbol);
        Stock stock = stockRepository.findBySymbolIgnoreCaseAndMarketCountry(symbol, marketCountry)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));

        if (!properties.enabled() || !stock.isTradable()) {
            throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
        }

        Instant now = clock.instant();
        MarketSessionStatus session = marketSessionProvider.currentSession(marketCountry, now);
        if (!session.open()) {
            throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
        }

        List<OrderBookRowProjection> rows = levelRepository.findActiveSnapshotRows(stock.getStockId());
        if (rows.size() != 20) {
            throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
        }

        OrderBookRowProjection header = rows.getFirst();
        now = clock.instant();
        String expectedCurrency = marketCountry == MarketCountry.KR ? "KRW" : "USD";

        if (!now.isBefore(session.validUntil())
                || header.getQuoteAt().isAfter(now)
                || header.getQuoteAt().isBefore(now.minus(properties.maxQuoteAge()))
                || !expectedCurrency.equals(stock.getCurrency())
                || !expectedCurrency.equals(header.getCurrency())) {
            throw new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE);
        }

        return OrderBookResponse.from(stock, rows);
    }
}
