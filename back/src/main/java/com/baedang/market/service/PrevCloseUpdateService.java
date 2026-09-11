package com.baedang.market.service;

import com.baedang.market.entity.DailyCandle;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** 서버 시작 및 주기적 복구를 담당한다. 기준가는 표시 중인 시세의 거래일에 맞춘다. */
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

    /** 랭킹 밖 종목의 상세 조회에서도 사용한다. last_price를 종가 대신 사용하지 않는다. */
    public boolean recover(Stock stock) {
        return coordinator.withStockLock(stock.getStockId(), () -> recoverLocked(stock));
    }

    private boolean recoverLocked(Stock stock) {
        MarketCountry country = stock.getMarketCountry();
        LocalDate completed = completedDays.resolve(country).orElse(null);
        if (completed == null) return false;
        OffsetDateTime closeAt = tradingDays.calendar(country, completed).regularCloseAt();
        if (closeAt == null) return false;
        QuoteSnapshot current = snapshots.findById(stock.getStockId()).orElse(null);
        Optional<LocalDate> currentDate = current == null ? Optional.<LocalDate>empty()
                : tradingDays.quoteTradeDate(country, current.getQuoteAt().toInstant());
        boolean retainCurrent = currentDate.isPresent() && !current.getQuoteAt().isBefore(closeAt);
        LocalDate quoteDate = retainCurrent ? currentDate.get() : completed;
        LocalDate referenceDate = tradingDays.previousTradingDay(country, quoteDate).orElse(null);
        if (referenceDate == null) return false;
        if (retainCurrent && referenceDate.equals(current.getPrevCloseDate()) && current.getPrevClose() != null) return false;

        if (retainCurrent && current.getPrevCloseDate() != null && !referenceDate.equals(current.getPrevCloseDate())) {
            quotePersistence.clearMismatchedReference(current, referenceDate);
        }
        // 기존 DB 일봉이 있어도 기준가가 없거나 날짜가 맞지 않으면 반드시 다시 조회한다.
        Instant requestedAt = clock.instant();
        List<Candle> fetched = data.fetchCandles(stock.getSymbol(), CandleInterval.ONE_DAY, 200);
        List<DailyCandle> verified = dailyPersistence.upsert(stock.getStockId(), stock.getCurrency(), country, fetched, requestedAt);
        DailyCandle reference = verified.stream().filter(row -> referenceDate.equals(row.getTradeDate())).findFirst().orElse(null);
        if (retainCurrent) return quotePersistence.repairReference(stock, current, reference) > 0;
        DailyCandle close = verified.stream().filter(row -> completed.equals(row.getTradeDate())).findFirst().orElse(null);
        if (close == null) return false;
        return quotePersistence.saveRecoveredClose(stock, current, close, reference,
                clock.instant().atOffset(ZoneOffset.UTC)) > 0;
    }
}
