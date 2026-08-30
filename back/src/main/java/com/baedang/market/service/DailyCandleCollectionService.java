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
 * 상위 종목 일봉 정기 수집 및 초기 백필 서비스.
 */
@Service
public class DailyCandleCollectionService {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleCollectionService.class);

    /** MARKET_DATA_CHART 공식 한도는 20 TPS이며, 현재 수집기는 여유를 두고 5 TPS로 호출합니다. */
    private static final int CHART_TPS = 5;
    /** 일별 정기 수집: 마감 봉 1개 */
    private static final int DAILY_CANDLE_COUNT = 1;
    /** 초기 백필: 약 1년치 거래일 250봉 */
    private static final int BACKFILL_CANDLE_COUNT = 250;
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

    /** 일봉이 없는 종목 대상 과거 250봉 초기 백필 */
    public void backfill(MarketCountry marketCountry) {
        List<Stock> stocks = stockRepository.findRankedByMarketCountry(
                marketCountry, PageRequest.of(0, universeSize));

        if (stocks.isEmpty()) {
            log.info("[daily-candle-backfill] 백필 대상 없음: market={}", marketCountry);
            return;
        }

        // 별도 상태 테이블 없이 최근 250봉을 항상 다시 요청합니다. UPSERT가 멱등이므로
        // 기존 행은 갱신되고, 이전 실행 중단이나 일일 수집 실패로 생긴 내부 누락도 복구됩니다.
        List<Stock> targets = stocks;

        log.info("[daily-candle-backfill] 백필 시작: market={} targets={}", marketCountry, targets.size());
        Pacer pacer = Pacer.forTps(CHART_TPS);
        int successCount = 0;

        for (Stock stock : targets) {
            try {
                List<Candle> candles = marketDataPort.fetchCandles(
                        stock.getSymbol(), CandleInterval.ONE_DAY, BACKFILL_CANDLE_COUNT);
                if (candles.isEmpty()) {
                    log.warn("[daily-candle-backfill] 빈 응답: market={} symbol={}",
                            marketCountry, stock.getSymbol());
                    continue;
                }
                persistenceService.upsert(
                        stock.getStockId(), stock.getMarketCountry(), stock.getCurrency(), candles);
                successCount++;
            } catch (Exception e) {
                log.warn("[daily-candle-backfill] 백필 실패: market={} symbol={} reason={}",
                        marketCountry, stock.getSymbol(), e.getMessage());
            } finally {
                pacer.pace();
            }
        }

        if (successCount == 0) {
            log.warn("[daily-candle-backfill] 전량 실패: market={} — Toss 어댑터 장애 가능성", marketCountry);
        } else {
            log.info("[daily-candle-backfill] 백필 완료: market={} success={}/{}", marketCountry, successCount, targets.size());
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
