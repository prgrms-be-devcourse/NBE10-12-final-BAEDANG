package com.baedang.market.service;

import com.baedang.market.config.QuoteCollectionProperties;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 200개 keyset 페이지를 제출합니다. 전체 목록/무제한 작업 큐를 메모리에 적재하지 않습니다. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class QuoteSnapshotLoadService {
    private final StockRepository stocks;
    private final QuoteSnapshotRepository snapshots;
    private final QuoteRefreshCoordinator coordinator;
    private final QuoteCollectionProperties properties;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Map<MarketCountry, Cursor> cursors = new EnumMap<>(MarketCountry.class);

    public QuoteSnapshotLoadService(StockRepository stocks, QuoteSnapshotRepository snapshots,
            QuoteRefreshCoordinator coordinator, QuoteCollectionProperties properties, Clock clock, MeterRegistry metrics) {
        this.stocks = stocks;
        this.snapshots = snapshots;
        this.coordinator = coordinator;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** 반환값은 저장 완료 건수가 아니라 제출/병합한 대상 수입니다. */
    public synchronized int syncQuotes(MarketCountry country, Instant sessionUntil) {
        Instant now = clock.instant();
        if (!now.isBefore(sessionUntil) || !coordinator.canSubmitBackground()) return 0;
        Cursor cursor = cursors.computeIfAbsent(country, ignored -> new Cursor());
        if (now.isBefore(cursor.nextAt)) return 0;
        if (cursor.startedAt == null) cursor.startedAt = now;
        List<Stock> page = stocks.findQuoteTargets(country, cursor.after,
                now.atOffset(ZoneOffset.UTC), PageRequest.of(0, 200));
        Duration interval = properties.refreshInterval();
        if (!page.isEmpty()) {
            Map<Long, QuoteSnapshot> existing = snapshots.findByStockIdIn(page.stream().map(Stock::getStockId).toList())
                    .stream().collect(Collectors.toMap(QuoteSnapshot::getStockId, Function.identity()));
            List<Stock> due = page.stream().filter(stock -> {
                QuoteSnapshot quote = existing.get(stock.getStockId());
                // 수집 빈도 조절일 뿐 체결 신선도 판정이 아닙니다. 체결은 원본 quoteAt을 검사합니다.
                return quote == null || quote.getCollectedAt().toInstant().isBefore(cursor.startedAt)
                        || quote.getCollectedAt().toInstant().isAfter(now);
            }).toList();
            if (!coordinator.submitBackground(due, sessionUntil)) return 0;
            cursor.after = page.getLast().getStockId();
        }
        if (page.size() < 200) {
            metrics.timer("quote.collection.sweep.submission", "market", country.name())
                    .record(Duration.between(cursor.startedAt, now).abs());
            cursor.nextAt = cursor.startedAt.plus(interval);
            cursor.startedAt = null;
            cursor.after = 0L;
        }
        return page.size();
    }

    private static final class Cursor {
        private long after;
        private Instant startedAt;
        private Instant nextAt = Instant.MIN;
    }
}
