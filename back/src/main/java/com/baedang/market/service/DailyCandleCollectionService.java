package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 상위 종목 일봉 정기 수집 서비스.
 */
@Service
public class DailyCandleCollectionService {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleCollectionService.class);

    /** 일별 정기 수집: 마감 봉 1개 */
    private static final int DAILY_CANDLE_COUNT = 1;

    private final MarketDataPort marketDataPort;
    private final StockRepository stockRepository;
    private final DailyCandlePersistenceService persistenceService;
    private final DailyCandleRepository dailyCandleRepository;
    private final MarketTradingDayPolicy tradingDays;
    private final DailyCandleFetchCoordinator coordinator;
    private final Clock clock;
    private final int universeSize;

    public DailyCandleCollectionService(
            MarketDataPort marketDataPort,
            StockRepository stockRepository,
            DailyCandlePersistenceService persistenceService,
            DailyCandleRepository dailyCandleRepository,
            MarketTradingDayPolicy tradingDays,
            Clock clock,
            DailyCandleFetchCoordinator coordinator,
            @Value("${trading.universe-size:100}") int universeSize
    ) {
        this.marketDataPort = marketDataPort;
        this.stockRepository = stockRepository;
        this.persistenceService = persistenceService;
        this.dailyCandleRepository = dailyCandleRepository;
        this.tradingDays = tradingDays;
        this.clock = clock;
        this.coordinator = coordinator;
        this.universeSize = universeSize;
    }

    /**
     * 장 마감 후 당일 마감 일봉 1개 수집.
     *
     * @return 당일 확정 일봉이 실제로 적재됐거나(성공) 이미 적재돼 있으면 {@code true}.
     *         전량 미적재(Toss 장애·확정 일봉 미도착)면 {@code false}. 휴장·마감 전·대상 없음 등
     *         "이번엔 수집할 게 없음"도 {@code false} 로 돌려, 호출자가 배치 성공 지표를 함부로
     *         갱신하지 않게 한다(거짓 성공 방지, PR #213 리뷰 반영).
     */
    public boolean collect(MarketCountry marketCountry) {
        Optional<CollectionContext> contextCandidate = collectionContext(marketCountry);
        if (contextCandidate.isEmpty()) return false;
        CollectionContext context = contextCandidate.get();

        List<Stock> stocks = stockRepository.findRankedByMarketCountry(
                marketCountry, PageRequest.of(0, universeSize));

        if (stocks.isEmpty()) {
            log.info("[daily-candle] 수집 대상 없음: market={}", marketCountry);
            return false;
        }

        List<Long> stockIds = stocks.stream().map(Stock::getStockId).toList();
        Set<Long> storedStockIds = dailyCandleRepository.findStoredStockIds(
                context.expectedTradeDate(), stockIds);
        List<Stock> targets = stocks.stream()
                .filter(stock -> !storedStockIds.contains(stock.getStockId()))
                .toList();

        if (targets.isEmpty()) {
            log.info("[daily-candle] 당일 수집 완료 상태: market={} tradeDate={}",
                    marketCountry, context.expectedTradeDate());
            return true; // 이미 당일 확정 일봉이 다 적재됨 = 데이터 존재 = 성공
        }

        log.info("[daily-candle] 수집 시작: market={} tradeDate={} targets={}/{}",
                marketCountry, context.expectedTradeDate(), targets.size(), stocks.size());
        int successCount = 0;

        for (Stock stock : targets) {
            try {
                boolean stored = coordinator.withStockLock(stock.getStockId(), () -> {
                    Instant requestedAt = clock.instant();
                    List<Candle> candles = marketDataPort.fetchCandles(
                            stock.getSymbol(), CandleInterval.ONE_DAY, DAILY_CANDLE_COUNT);
                    if (candles.isEmpty()) {
                        log.warn("[daily-candle] 빈 응답: market={} symbol={}", marketCountry, stock.getSymbol());
                        return false;
                    }
                    if (!hasExpectedTradeDate(candles, context.expectedTradeDate(), marketCountry)) {
                        log.warn("[daily-candle] 확정 일봉 미도착: market={} symbol={} expectedTradeDate={} actual={}",
                                marketCountry, stock.getSymbol(), context.expectedTradeDate(),
                                candles.stream().map(c -> tradeDate(c, marketCountry)).toList());
                        return false;
                    }
                    return persistenceService.upsert(stock.getStockId(), stock.getCurrency(),
                            stock.getMarketCountry(), candles, requestedAt).stream()
                            .anyMatch(row -> context.expectedTradeDate().equals(row.getTradeDate()));
                });
                if (stored) successCount++;
            } catch (Exception e) {
                log.warn("[daily-candle] 수집 실패: market={} symbol={} reason={}",
                        marketCountry, stock.getSymbol(), e.getMessage());
            }
        }

        if (successCount == 0) {
            log.warn("[daily-candle] 전량 미적재: market={} — 확정 일봉 미도착 또는 Toss 장애 가능성", marketCountry);
            return false; // 대상은 있었는데 하나도 못 적재 = 실패. 배치 성공 지표를 갱신하지 않는다.
        }
        log.info("[daily-candle] 수집 완료: market={} success={}/{}", marketCountry, successCount, targets.size());
        return true;
    }

    private Optional<CollectionContext> collectionContext(MarketCountry marketCountry) {
        if (marketCountry == null) {
            log.warn("[daily-candle] 시장 정보가 비어 있어 수집 생략");
            return Optional.empty();
        }
        Instant now = clock.instant();
        LocalDate tradeDate = now.atZone(marketCountry.zoneId()).toLocalDate();
        MarketCalendarDay calendarDay;
        try {
            calendarDay = tradingDays.calendar(marketCountry, tradeDate);
        } catch (Exception exception) {
            log.warn("[daily-candle] 시장 캘린더 조회 실패: market={} tradeDate={} reason={}",
                    marketCountry, tradeDate, exception.getMessage());
            return Optional.empty();
        }

        if (!calendarDay.isOpen()) {
            log.info("[daily-candle] 휴장일 수집 생략: market={} tradeDate={}", marketCountry, tradeDate);
            return Optional.empty();
        }
        if (!calendarDay.isFinalizedAt(now)) {
            log.warn("[daily-candle] 정규장 마감 전 수집 생략: market={} tradeDate={}",
                    marketCountry, tradeDate);
            return Optional.empty();
        }
        return Optional.of(new CollectionContext(calendarDay.tradeDate()));
    }

    private boolean hasExpectedTradeDate(List<Candle> candles, LocalDate expectedTradeDate, MarketCountry country) {
        return candles.stream().allMatch(candle -> expectedTradeDate.equals(tradeDate(candle, country)));
    }

    private LocalDate tradeDate(Candle candle, MarketCountry country) {
        return candle.candleAt().atZoneSameInstant(country.zoneId()).toLocalDate();
    }

    private record CollectionContext(LocalDate expectedTradeDate) {
    }
}
