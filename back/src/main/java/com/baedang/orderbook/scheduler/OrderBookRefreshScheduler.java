package com.baedang.orderbook.scheduler;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.orderbook.repository.OrderBookVersionRepository;
import com.baedang.orderbook.service.OrderBookGenerator;
import com.baedang.orderbook.service.OrderBookPublicationService;
import com.baedang.orderbook.service.OrderBookRetentionService;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 정규장 중 주기적인 가상 호가 갱신과 미소비 종료 버전 정리를 조율하는 스케줄러 (설계서 §5).
 *
 * <p>GET·주문·견적 요청은 호가를 생성하지 않으며, 이 스케줄러만이 생성을 트리거한다.
 * 시장별 전체 루프 트랜잭션은 사용하지 않고({@code Propagation.NEVER}), 종목별
 * publication/close 트랜잭션과 retention 트랜잭션을 격리 실행한다.
 */
@Component
@ConditionalOnProperty(
        prefix = "trading.orderbook",
        name = "enabled",
        havingValue = "true"
)
public class OrderBookRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderBookRefreshScheduler.class);

    private final MarketSessionProvider marketSessionProvider;
    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final OrderBookVersionRepository versionRepository;
    private final OrderBookGenerator generator;
    private final OrderBookPublicationService publicationService;
    private final OrderBookRetentionService retentionService;
    private final OrderBookProperties properties;
    private final Clock clock;

    public OrderBookRefreshScheduler(
            MarketSessionProvider marketSessionProvider,
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            OrderBookVersionRepository versionRepository,
            OrderBookGenerator generator,
            OrderBookPublicationService publicationService,
            OrderBookRetentionService retentionService,
            OrderBookProperties properties,
            Clock clock
    ) {
        this.marketSessionProvider = marketSessionProvider;
        this.stockRepository = stockRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.versionRepository = versionRepository;
        this.generator = generator;
        this.publicationService = publicationService;
        this.retentionService = retentionService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${trading.orderbook.refresh-interval:3s}",
            initialDelayString = "${trading.orderbook.refresh-initial-delay:0s}"
    )
    @Transactional(propagation = Propagation.NEVER)
    public void refreshOrderBooks() {
        for (MarketCountry country : List.of(MarketCountry.KR, MarketCountry.US)) {
            try {
                refreshMarket(country, clock.instant());
            } catch (RuntimeException exception) {
                log.error("가상 호가 시장 갱신 실패: {}", country, exception);
            }
        }
    }

    @Scheduled(
            fixedDelayString = "${trading.orderbook.unconsumed-retention:1m}",
            initialDelayString = "${trading.orderbook.retention-initial-delay:0s}"
    )
    @Transactional(propagation = Propagation.NEVER)
    public void deleteExpiredUnconsumedVersions() {
        try {
            retentionService.deleteExpiredUnconsumed();
        } catch (RuntimeException exception) {
            log.error("가상 호가 미소비 버전 정리 실패", exception);
        }
    }

    private void refreshMarket(MarketCountry country, Instant now) {
        MarketSessionStatus session;
        try {
            session = marketSessionProvider.currentSession(country, now);
        } catch (RuntimeException exception) {
            log.error("시장 세션 조회 실패로 호가 갱신을 건너뜁니다: {}", country, exception);
            return;
        }

        List<Long> activeStockIds = versionRepository.findActiveStockIdsByMarketCountry(country.name());

        if (!session.open()) {
            for (Long stockId : activeStockIds) {
                closeStockSafely(stockId);
            }
            return;
        }

        List<Stock> rankedStocks = stockRepository.findByMarketCountryAndIsRankedTrue(country);
        Set<Long> rankedStockIds = rankedStocks.stream()
                .map(Stock::getStockId)
                .collect(Collectors.toSet());

        // active IDs − ranked IDs에 해당하는 종목(랭킹 이탈 종목)을 별도 트랜잭션으로 종료
        for (Long activeId : activeStockIds) {
            if (!rankedStockIds.contains(activeId)) {
                closeStockSafely(activeId);
            }
        }

        if (rankedStocks.isEmpty()) {
            return;
        }

        Map<Long, QuoteSnapshot> quoteMap = quoteSnapshotRepository.findByStockIdIn(rankedStockIds)
                .stream()
                .collect(Collectors.toMap(QuoteSnapshot::getStockId, Function.identity(), (a, b) -> a));

        for (Stock stock : rankedStocks) {
            try {
                refreshStock(stock, quoteMap.get(stock.getStockId()), session.validUntil(), now);
            } catch (RuntimeException exception) {
                log.error("종목 호가 갱신 실패: {} ({})", stock.getSymbol(), stock.getStockId(), exception);
            }
        }
    }

    private void refreshStock(Stock stock, QuoteSnapshot quote, Instant sessionValidUntil, Instant now) {
        Long stockId = stock.getStockId();
        if (quote == null || quote.getQuoteAt() == null || quote.getLastPrice() == null) {
            publicationService.closeActive(stockId);
            return;
        }

        String expectedCurrency = stock.getMarketCountry() == MarketCountry.KR ? "KRW" : "USD";
        if (!expectedCurrency.equals(stock.getCurrency()) || !expectedCurrency.equals(quote.getCurrency())) {
            publicationService.closeActive(stockId);
            return;
        }

        Instant quoteAt = quote.getQuoteAt().toInstant();
        if (quoteAt.isAfter(now) || quoteAt.isBefore(now.minus(properties.maxQuoteAge())) || !stock.isTradable()) {
            publicationService.closeActive(stockId);
            return;
        }

        StockDescriptor descriptor = StockDescriptor.from(stock);
        long seed = ThreadLocalRandom.current().nextLong();
        GeneratedOrderBook generated;
        try {
            generated = generator.generate(properties, descriptor, quote.getLastPrice(), quoteAt, now, seed);
        } catch (IllegalArgumentException exception) {
            log.warn("호가 생성 거절(유효 가격/BID 불가): {} ({}) - {}", stock.getSymbol(), stockId, exception.getMessage());
            publicationService.closeActive(stockId);
            return;
        }

        publicationService.publish(generated, sessionValidUntil);
    }

    private void closeStockSafely(Long stockId) {
        try {
            publicationService.closeActive(stockId);
        } catch (RuntimeException exception) {
            log.error("활성 호가 종료 실패: stockId={}", stockId, exception);
        }
    }
}
