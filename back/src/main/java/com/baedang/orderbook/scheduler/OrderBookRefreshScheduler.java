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
import com.baedang.stock.service.StockTradingStatusService;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 정규장 중 주기적인 가상 호가 갱신과 종료 버전 정리를 조율하는 스케줄러 (설계서 §5).
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
    private final StockTradingStatusService statuses;

    public OrderBookRefreshScheduler(
            MarketSessionProvider marketSessionProvider,
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            OrderBookVersionRepository versionRepository,
            OrderBookGenerator generator,
            OrderBookPublicationService publicationService,
            OrderBookRetentionService retentionService,
            OrderBookProperties properties,
            Clock clock,
            StockTradingStatusService statuses
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
        this.statuses = statuses;
    }

    @Scheduled(
            fixedDelayString = "${trading.orderbook.refresh-interval}",
            initialDelayString = "${trading.orderbook.refresh-initial-delay}",
            scheduler = "orderBookTaskScheduler"
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
            fixedDelayString = "${trading.orderbook.closed-version-retention}",
            initialDelayString = "${trading.orderbook.retention-initial-delay}",
            scheduler = "orderBookTaskScheduler"
    )
    @Transactional(propagation = Propagation.NEVER)
    public void deleteExpiredClosedVersions() {
        try {
            retentionService.deleteExpiredClosed();
        } catch (RuntimeException exception) {
            log.error("가상 호가 종료 버전 정리 실패", exception);
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

        Set<Long> targetIds = new HashSet<>();
        long after = 0L;
        while (true) {
            List<Stock> targets = stockRepository.findQuoteTargets(country, after,
                    clock.instant().atOffset(ZoneOffset.UTC),
                    PageRequest.of(0, 200));
            targetIds.addAll(targets.stream().map(Stock::getStockId).toList());
            if (targets.isEmpty()) break;
            refreshPage(targets, session.validUntil());
            after = targets.getLast().getStockId();
            if (targets.size() < 200) break;
        }
        for (Long activeId : activeStockIds) {
            if (!targetIds.contains(activeId)) {
                try {
                    publicationService.closeIfNotTarget(activeId);
                } catch (RuntimeException exception) {
                    log.error("호가 대상 제외 처리 실패: stockId={}", activeId, exception);
                }
            }
        }
    }

    private void refreshPage(List<Stock> targets, Instant sessionUntil) {
        List<Stock> verified;
        try {
            verified = statuses.refreshBatch(targets);
        } catch (RuntimeException exception) {
            log.warn("종목 상태 확인 실패로 호가를 종료합니다", exception);
            targets.forEach(stock -> closeStockSafely(stock.getStockId()));
            return;
        }
        Set<Long> verifiedIds = verified.stream().map(Stock::getStockId).collect(Collectors.toSet());
        targets.stream().filter(stock -> !verifiedIds.contains(stock.getStockId()))
                .forEach(stock -> closeStockSafely(stock.getStockId()));
        Map<Long, QuoteSnapshot> quoteMap = quoteSnapshotRepository.findByStockIdIn(verifiedIds)
                .stream().collect(Collectors.toMap(QuoteSnapshot::getStockId, Function.identity()));
        for (Stock stock : verified) {
            try {
                refreshStock(stock, quoteMap.get(stock.getStockId()), sessionUntil, clock.instant());
            } catch (RuntimeException exception) {
                log.error("종목 호가 갱신 실패: stockId={}", stock.getStockId(), exception);
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
