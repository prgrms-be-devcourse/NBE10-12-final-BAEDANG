package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
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

import java.util.List;

/**
 * 거래대금 상위 종목의 일봉(Daily OHLCV)을 수집합니다.
 *
 * <p>역할 두 가지:
 * <ol>
 *   <li>{@link #collect(MarketCountry)} — 장 마감 직후 스케줄러가 호출. 최신 봉 1개를 저장합니다.</li>
 *   <li>{@link #backfill(MarketCountry)} — 앱 기동 시 {@code DailyCandleBackfillRunner} 가 호출.
 *       일봉이 한 개도 없는 종목에 대해 최대 250개(약 1년)의 과거 봉을 적재합니다.</li>
 * </ol>
 *
 * <p>두 경우 모두 {@link Pacer}로 {@code MARKET_DATA_CHART} 그룹 5 TPS 제한을 준수하며,
 * 개별 종목 수집 실패는 로그만 남기고 나머지 종목 수집을 계속합니다(배치 부분 실패 허용).
 * 전량 실패 시에는 Toss 어댑터 자체 장애 가능성이 높으므로 WARN 으로 격상합니다.
 */
@Service
public class DailyCandleCollectionService {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleCollectionService.class);

    /** MARKET_DATA_CHART 그룹 TPS 제한 (docs/erd.md) */
    private static final int CHART_TPS = 5;
    /** 일별 정기 수집: 마감 봉 1개만 확인 */
    private static final int DAILY_CANDLE_COUNT = 1;
    /** 초기 백필: 약 1년치 거래일 봉 */
    private static final int BACKFILL_CANDLE_COUNT = 250;

    private final MarketDataPort marketDataPort;
    private final StockRepository stockRepository;
    private final DailyCandleRepository dailyCandleRepository;
    private final DailyCandlePersistenceService persistenceService;
    private final int universeSize;

    public DailyCandleCollectionService(
            MarketDataPort marketDataPort,
            StockRepository stockRepository,
            DailyCandleRepository dailyCandleRepository,
            DailyCandlePersistenceService persistenceService,
            @Value("${trading.universe-size:100}") int universeSize
    ) {
        this.marketDataPort = marketDataPort;
        this.stockRepository = stockRepository;
        this.dailyCandleRepository = dailyCandleRepository;
        this.persistenceService = persistenceService;
        this.universeSize = universeSize;
    }

    /**
     * 지정 시장의 거래대금 상위 종목의 당일 마감 일봉 1개를 수집합니다.
     * 국내(15:40 KST), 해외(06:10 KST) 스케줄러에서 호출합니다.
     */
    public void collect(MarketCountry marketCountry) {
        List<Stock> stocks = stockRepository.findRankedByMarketCountry(
                marketCountry, PageRequest.of(0, universeSize));

        if (stocks.isEmpty()) {
            log.info("[daily-candle] 수집 대상 없음: market={}", marketCountry);
            return;
        }

        log.info("[daily-candle] 수집 시작: market={} stocks={}", marketCountry, stocks.size());
        Pacer pacer = Pacer.forTps(CHART_TPS);
        int successCount = 0;

        for (Stock stock : stocks) {
            try {
                List<Candle> candles = marketDataPort.fetchCandles(
                        stock.getSymbol(), CandleInterval.ONE_DAY, DAILY_CANDLE_COUNT);
                persistenceService.upsert(stock.getStockId(), stock.getCurrency(), candles);
                successCount++;
            } catch (Exception e) {
                log.info("[daily-candle] 수집 실패 무시: symbol={} reason={}", stock.getSymbol(), e.getMessage());
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

    /**
     * 일봉 데이터가 한 개도 없는 종목에 대해 초기 적재(Backfill)를 수행합니다.
     * 앱 기동 시 {@code DailyCandleBackfillRunner} 에서 호출합니다.
     *
     * <p>이미 일봉이 있는 종목은 건너뜁니다 — 재배포 시 불필요한 API 호출을 방지합니다.
     */
    public void backfill(MarketCountry marketCountry) {
        List<Stock> stocks = stockRepository.findRankedByMarketCountry(
                marketCountry, PageRequest.of(0, universeSize));

        List<Stock> targets = stocks.stream()
                .filter(stock -> !dailyCandleRepository.existsByStockId(stock.getStockId()))
                .toList();

        if (targets.isEmpty()) {
            log.info("[daily-candle-backfill] 백필 대상 없음: market={}", marketCountry);
            return;
        }

        log.info("[daily-candle-backfill] 백필 시작: market={} targets={}", marketCountry, targets.size());
        Pacer pacer = Pacer.forTps(CHART_TPS);
        int successCount = 0;

        for (Stock stock : targets) {
            try {
                List<Candle> candles = marketDataPort.fetchCandles(
                        stock.getSymbol(), CandleInterval.ONE_DAY, BACKFILL_CANDLE_COUNT);
                persistenceService.upsert(stock.getStockId(), stock.getCurrency(), candles);
                successCount++;
            } catch (Exception e) {
                log.info("[daily-candle-backfill] 백필 실패 무시: symbol={} reason={}", stock.getSymbol(), e.getMessage());
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
}
