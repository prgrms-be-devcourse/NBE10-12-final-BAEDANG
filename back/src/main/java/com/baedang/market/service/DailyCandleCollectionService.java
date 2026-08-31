package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.standard.utils.Pacer;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 상위 종목 일봉 정기 수집 서비스.
 */
@Service
public class DailyCandleCollectionService {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleCollectionService.class);

    /** MARKET_DATA_CHART 일봉 호출 한도 */
    private static final int CHART_TPS = 20;
    /** 일별 정기 수집: 마감 봉 1개 */
    private static final int DAILY_CANDLE_COUNT = 1;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final MarketDataPort marketDataPort;
    private final StockRepository stockRepository;
    private final DailyCandlePersistenceService persistenceService;
    private final DailyCandleRepository dailyCandleRepository;
    private final MarketCalendarPort marketCalendarPort;
    private final Clock clock;
    private final int universeSize;

    public DailyCandleCollectionService(
            MarketDataPort marketDataPort,
            StockRepository stockRepository,
            DailyCandlePersistenceService persistenceService,
            DailyCandleRepository dailyCandleRepository,
            MarketCalendarPort marketCalendarPort,
            Clock clock,
            @Value("${trading.universe-size:100}") int universeSize
    ) {
        this.marketDataPort = marketDataPort;
        this.stockRepository = stockRepository;
        this.persistenceService = persistenceService;
        this.dailyCandleRepository = dailyCandleRepository;
        this.marketCalendarPort = marketCalendarPort;
        this.clock = clock;
        this.universeSize = universeSize;
    }

    /** 장 마감 후 당일 마감 일봉 1개 수집 */
    public void collect(MarketCountry marketCountry) {
        Optional<CollectionContext> contextCandidate = collectionContext(marketCountry);
        if (contextCandidate.isEmpty()) return;
        CollectionContext context = contextCandidate.get();

        List<Stock> stocks = stockRepository.findRankedByMarketCountry(
                marketCountry, PageRequest.of(0, universeSize));

        if (stocks.isEmpty()) {
            log.info("[daily-candle] 수집 대상 없음: market={}", marketCountry);
            return;
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
            return;
        }

        log.info("[daily-candle] 수집 시작: market={} tradeDate={} targets={}/{}",
                marketCountry, context.expectedTradeDate(), targets.size(), stocks.size());
        Pacer pacer = Pacer.forTps(CHART_TPS);
        int successCount = 0;

        for (Stock stock : targets) {
            try {
                List<Candle> candles = marketDataPort.fetchCandles(
                        stock.getSymbol(), CandleInterval.ONE_DAY, DAILY_CANDLE_COUNT);
                if (candles.isEmpty()) {
                    log.warn("[daily-candle] 빈 응답: market={} symbol={}", marketCountry, stock.getSymbol());
                    continue;
                }
                if (!hasExpectedTradeDate(candles, context.expectedTradeDate())) {
                    log.warn("[daily-candle] 확정 일봉 미도착: market={} symbol={} expectedTradeDate={} actual={}",
                            marketCountry, stock.getSymbol(), context.expectedTradeDate(),
                            candles.stream().map(this::kstTradeDate).toList());
                    continue;
                }
                persistenceService.upsert(stock.getStockId(), stock.getCurrency(), candles);
                successCount++;
            } catch (Exception e) {
                log.warn("[daily-candle] 수집 실패: market={} symbol={} reason={}",
                        marketCountry, stock.getSymbol(), e.getMessage());
            } finally {
                pacer.pace();
            }
        }

        if (successCount == 0) {
            log.warn("[daily-candle] 전량 미적재: market={} — 확정 일봉 미도착 또는 Toss 장애 가능성", marketCountry);
        } else {
            log.info("[daily-candle] 수집 완료: market={} success={}/{}", marketCountry, successCount, targets.size());
        }
    }

    private Optional<CollectionContext> collectionContext(MarketCountry marketCountry) {
        if (marketCountry == null) {
            log.warn("[daily-candle] 시장 정보가 비어 있어 수집 생략");
            return Optional.empty();
        }
        Instant now = clock.instant();
        LocalDate tradeDate = now.atZone(marketCountry == MarketCountry.US ? NY : KST).toLocalDate();
        MarketCalendarDay calendarDay;
        try {
            calendarDay = switch (marketCountry) {
                case KR -> marketCalendarPort.fetchKrMarketCalendar(tradeDate);
                case US -> marketCalendarPort.fetchUsMarketCalendar(tradeDate);
            };
        } catch (Exception exception) {
            log.warn("[daily-candle] 시장 캘린더 조회 실패: market={} tradeDate={} reason={}",
                    marketCountry, tradeDate, exception.getMessage());
            return Optional.empty();
        }

        if (calendarDay == null
                || calendarDay.marketCountry() != marketCountry
                || !tradeDate.equals(calendarDay.tradeDate())) {
            log.warn("[daily-candle] 시장 캘린더 응답 불일치: market={} tradeDate={}",
                    marketCountry, tradeDate);
            return Optional.empty();
        }
        if (!calendarDay.isOpen()) {
            log.info("[daily-candle] 휴장일 수집 생략: market={} tradeDate={}", marketCountry, tradeDate);
            return Optional.empty();
        }
        if (calendarDay.regularCloseAt() == null
                || now.isBefore(calendarDay.regularCloseAt().plusMinutes(10).toInstant())) {
            log.warn("[daily-candle] 정규장 마감 전 수집 생략: market={} tradeDate={}",
                    marketCountry, tradeDate);
            return Optional.empty();
        }
        return Optional.of(new CollectionContext(calendarDay.tradeDate()));
    }

    private boolean hasExpectedTradeDate(List<Candle> candles, LocalDate expectedTradeDate) {
        return candles.stream().allMatch(candle -> expectedTradeDate.equals(kstTradeDate(candle)));
    }

    private LocalDate kstTradeDate(Candle candle) {
        return candle.candleAt().atZoneSameInstant(KST).toLocalDate();
    }

    private record CollectionContext(LocalDate expectedTradeDate) {
    }
}
