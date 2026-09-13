package com.baedang.orderbook.repository;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.model.TradingPriceLimits;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.orderbook.entity.OrderBookLevel;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.entity.OrderBookVersion;
import com.baedang.orderbook.model.LockedOrderBook;
import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.orderbook.port.OrderBookExecutionStore;
import com.baedang.orderbook.service.OrderBookPricePolicy;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JpaOrderBookExecutionStore implements OrderBookExecutionStore {

    private final OrderBookPricePolicy prices;
    private final StockRepository stocks;
    private final QuoteSnapshotRepository quotes;
    private final Clock clock;

    private final OrderBookVersionRepository versionRepository;
    private final OrderBookLevelRepository levelRepository;

    public JpaOrderBookExecutionStore(
            OrderBookVersionRepository versionRepository,
            OrderBookLevelRepository levelRepository, OrderBookPricePolicy prices,
            StockRepository stocks, QuoteSnapshotRepository quotes, Clock clock
    ) {
        this.versionRepository = versionRepository;
        this.prices = prices;
        this.stocks = stocks;
        this.quotes = quotes;
        this.clock = clock;
        this.levelRepository = levelRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedOrderBook> lockForExecution(
            Long stockId,
            Long expectedBookVersion,
            Long expectedRevision,
            OrderBookSide side
    ) {
        Objects.requireNonNull(stockId, "stockId는 필수입니다");
        Objects.requireNonNull(expectedBookVersion, "expectedBookVersion은 필수입니다");
        Objects.requireNonNull(expectedRevision, "expectedRevision은 필수입니다");
        Objects.requireNonNull(side, "side는 필수입니다");

        Optional<OrderBookVersion> version = versionRepository.findExpectedActiveForUpdate(
                stockId, expectedBookVersion, expectedRevision);
        if (version.isEmpty()) {
            return Optional.empty();
        }

        OrderBookVersion activeVersion = version.orElseThrow();
        List<OrderBookLevel> levels = side == OrderBookSide.ASK
                ? levelRepository.findAskLevelsForUpdate(expectedBookVersion)
                : levelRepository.findBidLevelsForUpdate(expectedBookVersion);

        Instant now = clock.instant();
        Stock stock = stocks.findById(stockId).orElseThrow();
        QuoteSnapshot quote = quotes.findById(stockId).orElse(null);
        TradingPriceLimits limits = TradingPriceLimits.from(quote);
        if (quote == null || !stock.getCurrency().equals(quote.getCurrency())
                || !OrderBookPricePolicy.VERSION.equals(activeVersion.getPolicyVersion())
                || !limits.usable(stock.getMarketCountry(), now)
                || !limits.contains(stock.getMarketCountry(), activeVersion.getBasePrice())
                || !activeVersion.getQuoteAt().toInstant().atZone(stock.getMarketCountry().zoneId()).toLocalDate()
                    .equals(now.atZone(stock.getMarketCountry().zoneId()).toLocalDate())) return Optional.empty();
        if (!prices.matches(StockDescriptor.from(stock), activeVersion.getBasePrice(), activeVersion.getPolicyVersion(),
                side, limits, now, levels, OrderBookLevel::getPrice, OrderBookLevel::getLevelDepth)) {
            throw new IllegalStateException("활성 호가의 가격 배열이 생성 규칙과 다릅니다");
        }

        return Optional.of(new LockedOrderBook(activeVersion, levels));
    }

}
