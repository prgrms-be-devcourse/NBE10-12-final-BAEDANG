package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.DailyCandle;
import com.baedang.market.entity.MinuteCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.repository.MinuteCandleRepository;
import com.baedang.stock.dto.CandleResponse;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.model.CandleQuery;
import com.baedang.stock.model.CandleQueryInterval;
import com.baedang.stock.repository.StockRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class CandleQueryService {

    private static final Duration MINUTE_FRESHNESS = Duration.ofSeconds(60);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int REFRESH_LOCK_STRIPES = 64;

    private final CandleQueryPolicy candleQueryPolicy;
    private final StockRepository stockRepository;
    private final DailyCandleRepository dailyCandleRepository;
    private final MinuteCandleRepository minuteCandleRepository;
    private final MarketDataPort marketDataPort;
    private final MinuteCandlePersistenceService persistenceService;
    private final MinuteCandleFetchCache fetchCache;
    private final Clock clock;
    private final ReentrantLock[] refreshLocks = createRefreshLocks();

    public CandleQueryService(
            CandleQueryPolicy candleQueryPolicy,
            StockRepository stockRepository,
            DailyCandleRepository dailyCandleRepository,
            MinuteCandleRepository minuteCandleRepository,
            MarketDataPort marketDataPort,
            MinuteCandlePersistenceService persistenceService,
            MinuteCandleFetchCache fetchCache,
            Clock clock
    ) {
        this.candleQueryPolicy = candleQueryPolicy;
        this.stockRepository = stockRepository;
        this.dailyCandleRepository = dailyCandleRepository;
        this.minuteCandleRepository = minuteCandleRepository;
        this.marketDataPort = marketDataPort;
        this.persistenceService = persistenceService;
        this.fetchCache = fetchCache;
        this.clock = clock;
    }

    public CandleResponse getCandles(
            String symbol,
            String marketCountry,
            String interval,
            String range
    ) {
        MarketCountry country = candleQueryPolicy.parseMarketCountry(marketCountry);
        CandleQuery query = candleQueryPolicy.parse(interval, range);
        Stock stock = stockRepository
                .findBySymbolIgnoreCaseAndMarketCountry(normalizeSymbol(symbol), country)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));

        List<CandleResponse.Item> items = switch (query.interval()) {
            case ONE_MINUTE -> minuteItems(stock, query.count());
            case ONE_DAY -> dailyItems(stock, query.count());
        };
        return new CandleResponse(
                stock.getSymbol(),
                query.interval().value(),
                query.range().value(),
                stock.getCurrency(),
                items);
    }

    private List<CandleResponse.Item> minuteItems(Stock stock, int count) {
        refreshMinuteCandlesIfNeeded(stock, count);
        List<MinuteCandle> rows = new ArrayList<>(minuteCandleRepository
                .findByStockIdOrderByCandleAtDesc(stock.getStockId(), PageRequest.of(0, count)));
        Collections.reverse(rows);
        return rows.stream()
                .map(row -> CandleResponse.Item.of(
                        row.getCandleAt(),
                        row.getOpenPrice(),
                        row.getHighPrice(),
                        row.getLowPrice(),
                        row.getClosePrice(),
                        row.getVolume(),
                        stock.getCurrency()))
                .toList();
    }

    private List<CandleResponse.Item> dailyItems(Stock stock, int count) {
        List<DailyCandle> rows = new ArrayList<>(dailyCandleRepository
                .findByStockIdOrderByTradeDateDesc(stock.getStockId(), PageRequest.of(0, count)));
        Collections.reverse(rows);
        return rows.stream()
                .map(row -> CandleResponse.Item.of(
                        row.getTradeDate().atStartOfDay(KST).toOffsetDateTime(),
                        row.getOpenPrice(),
                        row.getHighPrice(),
                        row.getLowPrice(),
                        row.getClosePrice(),
                        row.getVolume(),
                        stock.getCurrency()))
                .toList();
    }

    private void refreshMinuteCandlesIfNeeded(Stock stock, int count) {
        Instant now = clock.instant();
        if (hasFreshMinuteData(stock.getStockId(), now)) return;

        ReentrantLock lock = refreshLockFor(stock.getStockId());
        lock.lock();
        try {
            now = clock.instant();
            if (hasFreshMinuteData(stock.getStockId(), now)) return;

            List<Candle> candles = marketDataPort.fetchCandles(
                    stock.getSymbol(), CandleInterval.ONE_MINUTE, count);
            CandleCurrencyValidator.validate(stock, candles);
            persistenceService.upsert(stock.getStockId(), candles);
            fetchCache.markFetched(stock.getStockId(), clock.instant());
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock refreshLockFor(Long stockId) {
        int index = Long.hashCode(stockId) & (REFRESH_LOCK_STRIPES - 1);
        return refreshLocks[index];
    }

    private ReentrantLock[] createRefreshLocks() {
        ReentrantLock[] locks = new ReentrantLock[REFRESH_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private boolean hasFreshMinuteData(Long stockId, Instant now) {
        if (fetchCache.isFresh(stockId, now)) return true;
        return minuteCandleRepository.findTopByStockIdOrderByCandleAtDesc(stockId)
                .map(MinuteCandle::getCandleAt)
                .map(OffsetDateTime::toInstant)
                .filter(candleAt -> !candleAt.isAfter(now))
                .filter(candleAt -> candleAt.plus(MINUTE_FRESHNESS).isAfter(now))
                .isPresent();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new BusinessException(ErrorCode.STOCK_NOT_FOUND);
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
