package com.baedang.market.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.model.PrevCloseUpdateResult;
import com.baedang.market.port.*;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Startup and periodic recovery. The displayed quote's trade date owns its reference. */
@Service
public class PrevCloseUpdateService {
    private static final Logger log = LoggerFactory.getLogger(PrevCloseUpdateService.class);
    private final StockRepository stocks;
    private final QuoteSnapshotRepository snapshots;
    private final LatestCompletedTradingDayResolver completedDays;
    private final MarketTradingDayPolicy tradingDays;
    private final MarketDataPort data;
    private final DailyCandlePersistenceService dailyPersistence;
    private final QuoteSnapshotPersistenceService quotePersistence;
    private final Clock clock;
    private final DailyCandleFetchCoordinator coordinator;

    public PrevCloseUpdateService(StockRepository stocks, QuoteSnapshotRepository snapshots,
            LatestCompletedTradingDayResolver completedDays,
            MarketTradingDayPolicy tradingDays, MarketDataPort data,
            DailyCandlePersistenceService dailyPersistence, QuoteSnapshotPersistenceService quotePersistence,
            Clock clock, DailyCandleFetchCoordinator coordinator) {
        this.stocks = stocks;
        this.snapshots = snapshots;
        this.completedDays = completedDays;
        this.tradingDays = tradingDays;
        this.data = data;
        this.dailyPersistence = dailyPersistence;
        this.quotePersistence = quotePersistence;
        this.clock = clock;
        this.coordinator = coordinator;
    }

    public PrevCloseUpdateResult update(MarketCountry country) {
        int targets = 0, recovered = 0;
        long after = 0;
        while (true) {
            List<Stock> page = stocks.findQuoteTargets(country, after,
                    clock.instant().atOffset(ZoneOffset.UTC), PageRequest.of(0, 100));
            for (Stock stock : page) {
                targets++;
                try {
                    if (recover(stock)) recovered++;
                } catch (RuntimeException exception) {
                    log.warn("[prev-close] recovery deferred: stockId={} type={}", stock.getStockId(), exception.getClass().getSimpleName());
                }
            }
            if (page.size() < 100) break;
            after = page.getLast().getStockId();
        }
        log.info("[prev-close] checked: market={} target={} updated={}", country, targets, recovered);
        return new PrevCloseUpdateResult(targets, recovered);
    }

    /** Also used by detail reads for non-ranked stocks. Never substitutes last_price for a close. */
    public boolean recover(Stock stock) {
        return coordinator.withStockLock(stock.getStockId(), () -> recoverLocked(stock));
    }

    private boolean recoverLocked(Stock stock) {
        var country = stock.getMarketCountry();
        var completed = completedDays.resolve(country).orElse(null);
        if (completed == null) return false;
        var closeAt = tradingDays.calendar(country, completed).regularCloseAt();
        if (closeAt == null) return false;
        QuoteSnapshot current = snapshots.findById(stock.getStockId()).orElse(null);
        var currentDate = current == null ? Optional.<LocalDate>empty()
                : tradingDays.quoteTradeDate(country, current.getQuoteAt().toInstant());
        boolean retainCurrent = currentDate.isPresent() && !current.getQuoteAt().isBefore(closeAt);
        var quoteDate = retainCurrent ? currentDate.get() : completed;
        var referenceDate = tradingDays.previousTradingDay(country, quoteDate).orElse(null);
        if (referenceDate == null) return false;
        if (retainCurrent && referenceDate.equals(current.getPrevCloseDate()) && current.getPrevClose() != null) return false;

        if (retainCurrent && current.getPrevCloseDate() != null && !referenceDate.equals(current.getPrevCloseDate())) {
            quotePersistence.clearMismatchedReference(current, referenceDate);
        }
        // Always refetch missing/mismatched reference evidence, even when old DB rows exist.
        var requestedAt = clock.instant();
        var fetched = data.fetchCandles(stock.getSymbol(), CandleInterval.ONE_DAY, 200);
        var verified = dailyPersistence.upsert(stock.getStockId(), stock.getCurrency(), country, fetched, requestedAt);
        var reference = verified.stream().filter(row -> referenceDate.equals(row.getTradeDate())).findFirst().orElse(null);
        if (retainCurrent) return quotePersistence.repairReference(stock, current, reference) > 0;
        var close = verified.stream().filter(row -> completed.equals(row.getTradeDate())).findFirst().orElse(null);
        if (close == null) return false;
        return quotePersistence.saveRecoveredClose(stock, current, close, reference,
                clock.instant().atOffset(ZoneOffset.UTC)) > 0;
    }
}
