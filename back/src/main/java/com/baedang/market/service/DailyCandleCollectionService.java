package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
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
    private final MarketCalendarPort marketCalendarPort;
    private final Clock clock;
    private final int universeSize;

    public DailyCandleCollectionService(
            MarketDataPort marketDataPort,
            StockRepository stockRepository,
            DailyCandlePersistenceService persistenceService,
            MarketCalendarPort marketCalendarPort,
            Clock clock,
            @Value("${trading.universe-size:100}") int universeSize
    ) {
        this.marketDataPort = marketDataPort;
        this.stockRepository = stockRepository;
        this.persistenceService = persistenceService;
        this.marketCalendarPort = marketCalendarPort;
        this.clock = clock;
        this.universeSize = universeSize;
    }

    /** 장 마감 후 당일 마감 일봉 1개 수집 */
    public void collect(MarketCountry marketCountry) {
        List<Stock> stocks = stockRepository.findRankedByMarketCountry(
                marketCountry, PageRequest.of(0, universeSize));

        if (stocks.isEmpty()) {
            log.info("[daily-candle] 수집 대상 없음: market={}", marketCountry);
            return;
        }

        if (!canCollect(marketCountry)) return;

        log.info("[daily-candle] 수집 시작: market={} stocks={}", marketCountry, stocks.size());
        Pacer pacer = Pacer.forTps(CHART_TPS);
        int successCount = 0;

        for (Stock stock : stocks) {
            try {
                List<Candle> candles = marketDataPort.fetchCandles(
                        stock.getSymbol(), CandleInterval.ONE_DAY, DAILY_CANDLE_COUNT);
                if (candles.isEmpty()) {
                    log.warn("[daily-candle] 빈 응답: market={} symbol={}", marketCountry, stock.getSymbol());
                    continue;
                }
                persistenceService.upsert(
                        stock.getStockId(), stock.getMarketCountry(), stock.getCurrency(), candles);
                successCount++;
            } catch (Exception e) {
                log.warn("[daily-candle] 수집 실패: market={} symbol={} reason={}",
                        marketCountry, stock.getSymbol(), e.getMessage());
            } finally {
                pacer.pace();
            }
        }

        if (successCount == 0) {
            log.warn("[daily-candle] 전량 실패: market={} — Toss 어댑터 장애 가능성", marketCountry);
        } else {
            log.info("[daily-candle] 수집 완료: market={} success={}/{}", marketCountry, successCount, stocks.size());
        }
    }

    private boolean canCollect(MarketCountry marketCountry) {
        Instant now = clock.instant();
        LocalDate tradeDate = now.atZone(marketCountry == MarketCountry.US ? NY : KST).toLocalDate();
        MarketCalendarDay calendarDay = switch (marketCountry) {
            case KR -> marketCalendarPort.fetchKrMarketCalendar(tradeDate);
            case US -> marketCalendarPort.fetchUsMarketCalendar(tradeDate);
        };

        if (calendarDay == null
                || calendarDay.marketCountry() != marketCountry
                || !tradeDate.equals(calendarDay.tradeDate())) {
            log.warn("[daily-candle] 시장 캘린더 응답 불일치: market={} tradeDate={}",
                    marketCountry, tradeDate);
            return false;
        }
        if (!calendarDay.isOpen()) {
            log.info("[daily-candle] 휴장일 수집 생략: market={} tradeDate={}", marketCountry, tradeDate);
            return false;
        }
        if (calendarDay.regularCloseAt() == null
                || now.isBefore(calendarDay.regularCloseAt().plusMinutes(10).toInstant())) {
            log.warn("[daily-candle] 정규장 마감 전 수집 생략: market={} tradeDate={}",
                    marketCountry, tradeDate);
            return false;
        }
        return true;
    }
}
