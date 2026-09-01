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
import java.util.Set;

/**
 * 상위 종목 과거 일봉 초기 시드 적재(Seed Backfill) 서비스.
 *
 * <p>신규 배포·로컬 DB 초기화 직후 {@code daily_candle} 이 비어 있는 Cold Start 상태에서,
 * 상위 종목에 대해 토스 1회 상한인 최근 {@value #SEED_CANDLE_COUNT}일치 일봉을 한 번에 적재해
 * 배포 즉시 풍성한 차트를 제공합니다.
 *
 * <p><b>정기 수집({@link DailyCandleCollectionService})과 목적이 다릅니다.</b> 정기 수집은
 * 장 마감 후 <b>당일 확정 마감봉 1개</b>를 누적하므로 시장 캘린더 게이팅(휴장·마감시각·확정일자
 * 검증)을 합니다. 시드는 <b>과거 이력 대량 조회</b>라 "오늘"과 무관하므로 그 게이팅을 하지 않습니다.
 *
 * <p><b>트리거는 관리자 수동 호출입니다.</b> 마스터·랭킹 적재가 끝나 유니버스가 채워진 뒤 호출해야
 * 하며(그전에는 대상이 비어 no-op), {@code MARKET_DATA_CHART} TPS 그룹을 정기 수집과 공유하므로
 * 다른 차트 수집이 없는 시간대에 실행합니다.
 */
@Service
public class DailyCandleSeedService {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleSeedService.class);

    /** 토스 1회 요청 상한이자 시드 1종목당 수집 일수. */
    private static final int SEED_CANDLE_COUNT = 200;

    private final MarketDataPort marketDataPort;
    private final StockRepository stockRepository;
    private final DailyCandlePersistenceService persistenceService;
    private final DailyCandleRepository dailyCandleRepository;
    private final int universeSize;
    private final int seedChartTps;

    public DailyCandleSeedService(
            MarketDataPort marketDataPort,
            StockRepository stockRepository,
            DailyCandlePersistenceService persistenceService,
            DailyCandleRepository dailyCandleRepository,
            @Value("${trading.universe-size:100}") int universeSize,
            @Value("${toss.seed-chart-tps:5}") int seedChartTps
    ) {
        this.marketDataPort = marketDataPort;
        this.stockRepository = stockRepository;
        this.persistenceService = persistenceService;
        this.dailyCandleRepository = dailyCandleRepository;
        this.universeSize = universeSize;
        this.seedChartTps = seedChartTps;
    }

    /** 모든 시장을 순차 시드합니다. 동시 실행은 TPS 그룹 예산을 초과할 수 있어 순차로 처리합니다. */
    public SeedResult seedAll() {
        SeedResult total = SeedResult.empty();
        for (MarketCountry marketCountry : MarketCountry.values()) {
            total = total.plus(seed(marketCountry));
        }
        return total;
    }

    /** 한 시장의 상위 종목 중 일봉 이력이 없는 종목만 200일치를 수집·적재합니다. */
    public SeedResult seed(MarketCountry marketCountry) {
        List<Stock> stocks = stockRepository.findRankedByMarketCountry(
                marketCountry, PageRequest.of(0, universeSize));
        if (stocks.isEmpty()) {
            log.warn("[daily-candle-seed] 유니버스 비어 있음 — 마스터·랭킹 적재 후 실행하세요: market={}",
                    marketCountry);
            return SeedResult.empty();
        }

        List<Long> stockIds = stocks.stream().map(Stock::getStockId).toList();
        Set<Long> alreadySeeded = dailyCandleRepository.findStockIdsWithAnyCandle(stockIds);
        List<Stock> targets = stocks.stream()
                .filter(stock -> !alreadySeeded.contains(stock.getStockId()))
                .toList();

        int skipped = stocks.size() - targets.size();
        if (targets.isEmpty()) {
            log.info("[daily-candle-seed] 이미 전량 시드됨: market={} total={}", marketCountry, stocks.size());
            return new SeedResult(stocks.size(), 0, 0, skipped);
        }

        log.info("[daily-candle-seed] 시드 시작: market={} targets={}/{} tps={}",
                marketCountry, targets.size(), stocks.size(), seedChartTps);
        Pacer pacer = Pacer.forTps(seedChartTps);
        int success = 0;
        int failure = 0;

        for (Stock stock : targets) {
            try {
                List<Candle> candles = marketDataPort.fetchCandles(
                        stock.getSymbol(), CandleInterval.ONE_DAY, SEED_CANDLE_COUNT);
                if (candles.isEmpty()) {
                    log.warn("[daily-candle-seed] 빈 응답: market={} symbol={}",
                            marketCountry, stock.getSymbol());
                    failure++;
                    continue;
                }
                persistenceService.upsert(stock.getStockId(), stock.getCurrency(), candles);
                success++;
            } catch (Exception e) {
                failure++;
                log.warn("[daily-candle-seed] 시드 실패: market={} symbol={} reason={}",
                        marketCountry, stock.getSymbol(), e.getMessage());
            } finally {
                pacer.pace();
            }
        }

        log.info("[daily-candle-seed] 시드 완료: market={} success={} failure={} skipped={} total={}",
                marketCountry, success, failure, skipped, stocks.size());
        return new SeedResult(stocks.size(), success, failure, skipped);
    }

    /** 시드 처리 결과 요약. total = 유니버스 크기, success/failure = 수집 시도 결과, skipped = 이미 이력 보유. */
    public record SeedResult(int total, int success, int failure, int skipped) {
        static SeedResult empty() {
            return new SeedResult(0, 0, 0, 0);
        }

        SeedResult plus(SeedResult other) {
            return new SeedResult(
                    total + other.total,
                    success + other.success,
                    failure + other.failure,
                    skipped + other.skipped);
        }
    }
}
