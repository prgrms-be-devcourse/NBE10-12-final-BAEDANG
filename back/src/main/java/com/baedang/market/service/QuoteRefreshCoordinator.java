package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.config.QuoteCollectionProperties;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.Stock;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 배경/사용자 현재가 요청의 단일 진입점. 진행 중인 종목만 제한된 맵에 유지합니다.
 * 배경 실행기에는 대기열이 없고 사용자 요청은 별도 1개 슬롯을 사용합니다.
 * 최종 HTTP TPS 제한은 Toss 클라이언트가 담당하며 여기서 permit을 중복 소비하지 않습니다.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class QuoteRefreshCoordinator {
    private static final Logger log = LoggerFactory.getLogger(QuoteRefreshCoordinator.class);
    private final MarketDataPort marketData;
    private final QuoteSnapshotPersistenceService persistence;
    private final QuoteSnapshotRepository snapshots;
    private final TaskExecutor executor;
    private final QuoteCollectionProperties properties;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Semaphore backgroundSlots;
    private final Semaphore urgentSlot = new Semaphore(1, true);
    private final Map<Long, CompletableFuture<Void>> inFlight = new HashMap<>();
    private Instant nextBackgroundAt = Instant.MIN;

    public QuoteRefreshCoordinator(MarketDataPort marketData, QuoteSnapshotPersistenceService persistence,
            QuoteSnapshotRepository snapshots, @Qualifier("quoteCollectionExecutor") TaskExecutor executor,
            QuoteCollectionProperties properties, Clock clock, MeterRegistry metrics) {
        this.marketData = marketData;
        this.persistence = persistence;
        this.snapshots = snapshots;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.backgroundSlots = new Semaphore(properties.backgroundConcurrency());
        metrics.gauge("quote.collection.inflight", this, QuoteRefreshCoordinator::inFlightCount);
    }

    private synchronized int inFlightCount() { return inFlight.size(); }

    public synchronized boolean canSubmitBackground() {
        return !clock.instant().isBefore(nextBackgroundAt) && backgroundSlots.availablePermits() > 0;
    }

    /** false면 요청을 제출하지 않았으므로 호출자는 페이지 커서를 유지합니다. */
    public boolean submitBackground(List<Stock> stocks, Instant sessionUntil) {
        if (stocks.isEmpty()) return true;
        if (stocks.size() > 200) throw new IllegalArgumentException("현재가 배치는 최대 200종목입니다");
        List<Stock> claimed = new ArrayList<>();
        synchronized (this) {
            if (!clock.instant().isBefore(sessionUntil)) return false;
            if (clock.instant().isBefore(nextBackgroundAt) || !backgroundSlots.tryAcquire()) return false;
            long additional = stocks.stream().map(Stock::getStockId).distinct().filter(id -> !inFlight.containsKey(id)).count();
            if (inFlight.size() + additional > properties.maxInFlightStocks()) {
                backgroundSlots.release();
                return false;
            }
            for (Stock stock : stocks) {
                if (!inFlight.containsKey(stock.getStockId())) {
                    inFlight.put(stock.getStockId(), new CompletableFuture<>());
                    claimed.add(stock);
                }
            }
            if (claimed.isEmpty()) {
                backgroundSlots.release();
                return true;
            }
            nextBackgroundAt = clock.instant().plusNanos(1_000_000_000L / properties.backgroundRequestsPerSecond());
        }
        try {
            executor.execute(() -> {
                try {
                    if (!clock.instant().isBefore(sessionUntil)) {
                        finish(claimed, new BusinessException(ErrorCode.MARKET_CLOSED));
                    } else {
                        fetch(claimed);
                    }
                } finally {
                    backgroundSlots.release();
                }
            });
        } catch (RuntimeException exception) {
            finish(claimed, exception);
            backgroundSlots.release();
            return false;
        }
        return true;
    }

    /** #141 주문/견적용. 조회 실패나 stale 값을 성공으로 돌려주지 않습니다. */
    public QuoteSnapshot requireFresh(Stock stock, Duration maxAge) {
        if (maxAge == null || maxAge.isNegative()) throw new IllegalArgumentException("신선도 범위가 필요합니다");
        QuoteSnapshot existing = snapshots.findById(stock.getStockId()).orElse(null);
        if (fresh(existing, maxAge)) return existing;
        QuoteSnapshot refreshed = refresh(stock);
        if (refreshed == null) throw new BusinessException(ErrorCode.QUOTE_NOT_FOUND);
        if (refreshed.getQuoteAt().toInstant().isAfter(clock.instant())) throw new BusinessException(ErrorCode.FUTURE_QUOTE);
        if (!fresh(refreshed, maxAge)) throw new BusinessException(ErrorCode.STALE_QUOTE);
        return refreshed;
    }

    private boolean fresh(QuoteSnapshot snapshot, Duration maxAge) {
        if (snapshot == null || snapshot.getQuoteAt() == null) return false;
        Instant now = clock.instant();
        Instant at = snapshot.getQuoteAt().toInstant();
        return !at.isAfter(now) && !at.isBefore(now.minus(maxAge));
    }

    /** 화면 조회도 동일 진행 요청을 공유합니다. 기존값 fallback 여부는 화면 호출자가 결정합니다. */
    public QuoteSnapshot refresh(Stock stock) {
        CompletableFuture<Void> result;
        boolean owner;
        synchronized (this) {
            result = inFlight.get(stock.getStockId());
            owner = result == null;
            if (owner) {
                if (inFlight.size() >= properties.maxInFlightStocks()) throw new BusinessException(ErrorCode.TOSS_RATE_LIMITED);
                result = new CompletableFuture<>();
                inFlight.put(stock.getStockId(), result);
            }
        }
        if (owner) {
            boolean acquired = false;
            try {
                acquired = urgentSlot.tryAcquire(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (!acquired) throw new BusinessException(ErrorCode.TOSS_RATE_LIMITED);
                fetch(List.of(stock));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                finish(List.of(stock), exception);
            } catch (RuntimeException exception) {
                finish(List.of(stock), exception);
            } finally {
                if (acquired) urgentSlot.release();
            }
        }
        try {
            result.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.TOSS_API_ERROR);
        } catch (TimeoutException exception) {
            // 실행 중인 HTTP 요청은 맵에 남겨 다음 요청과도 병합합니다.
            throw new BusinessException(ErrorCode.TOSS_API_ERROR);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.TOSS_API_ERROR);
        }
        return snapshots.findById(stock.getStockId()).orElse(null);
    }

    private void fetch(List<Stock> stocks) {
        Instant started = clock.instant();
        try {
            List<PriceQuote> quotes = marketData.fetchPrices(stocks.stream().map(Stock::getSymbol).toList());
            int updated = persistence.saveOrUpdate(stocks, quotes, clock.instant().atOffset(ZoneOffset.UTC));
            for (PriceQuote quote : quotes) {
                if (quote != null && quote.quoteAt() != null && !quote.quoteAt().toInstant().isAfter(clock.instant())) {
                    metrics.timer("quote.collection.source.age").record(
                            Duration.between(quote.quoteAt().toInstant(), clock.instant()));
                }
            }
            metrics.counter("quote.collection.updated").increment(updated);
            metrics.counter("quote.collection.requested").increment(stocks.size());
            finish(stocks, null);
        } catch (RuntimeException exception) {
            metrics.counter("quote.collection.failures").increment();
            log.warn("현재가 배치 조회/저장 실패: count={} type={}", stocks.size(), exception.getClass().getSimpleName());
            finish(stocks, exception);
        } finally {
            metrics.timer("quote.collection.batch").record(Duration.between(started, clock.instant()).abs());
        }
    }

    private synchronized void finish(List<Stock> stocks, Throwable error) {
        for (Stock stock : stocks) {
            CompletableFuture<Void> future = inFlight.remove(stock.getStockId());
            if (future == null) continue;
            if (error == null) future.complete(null);
            else future.completeExceptionally(error);
        }
    }
}
