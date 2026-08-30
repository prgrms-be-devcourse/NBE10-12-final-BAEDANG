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
 * 상위 종목 일봉 정기 수집 및 초기 백필 서비스.
 */
@Service
public class DailyCandleCollectionService {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleCollectionService.class);

    /** MARKET_DATA_CHART 그룹 5 TPS 제한 */
    private static final int CHART_TPS = 5;
    /** 일별 정기 수집: 마감 봉 1개 */
    private static final int DAILY_CANDLE_COUNT = 1;
    /** 초기 백필: 약 1년치 거래일 250봉 */
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

    /** 장 마감 후 당일 마감 일봉 1개 수집 */
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

    /** 일봉이 없는 종목 대상 과거 250봉 초기 백필 */
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
