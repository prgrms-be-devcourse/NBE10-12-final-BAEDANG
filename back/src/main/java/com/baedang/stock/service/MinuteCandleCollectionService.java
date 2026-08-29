package com.baedang.stock.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.standard.utils.Pacer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * 국내/해외 거래대금 상위 100종목의 1분봉을 정규장 동안 매분 수집해 minute_candle에
 * 적재한다 (docs/erd.md·docs/api-spec.md의 수집 스케줄 참고). 실제 트리거는
 * {@code com.baedang.stock.scheduler.MinuteCandleCollectionScheduler}가 매분 정각에
 * 호출한다 — 이 클래스는 순수 로직만 담아 스프링 스케줄링 없이 단위 테스트한다.
 *
 * <p>종목 상세 화면이 쓰는 온디맨드 조회({@link CandleQueryService})와는 별개 경로다.
 * 온디맨드는 "지금 보고 있는 종목 하나"를 요청-응답 안에서 처리하지만, 이건 배치라
 * 종목 하나가 실패해도 나머지 수집을 계속해야 한다.
 */
@Service
public class MinuteCandleCollectionService {

    private static final Logger log = LoggerFactory.getLogger(MinuteCandleCollectionService.class);

    /** MARKET_DATA_CHART 그룹 TPS — Toss 쪽 rate limit 기준 (docs/erd.md). */
    private static final int CANDLE_TPS = 5;
    /** 매분 최신 캔들 1개면 충분하지만, 스케줄 틱이 한 번 밀려도(배포·재시작 등)
     * 따라잡을 수 있게 여유를 둔다. */
    private static final int FETCH_COUNT = 2;

    private final MarketSessionProvider marketSessionProvider;
    private final StockRepository stockRepository;
    private final MarketDataPort marketDataPort;
    private final MinuteCandlePersistenceService persistenceService;
    private final Clock clock;
    /** "상위 100종목"이 이 기능의 요구사항이다 — is_ranked=true 행이 그 개수를 넘지
     * 않는다는 건 랭킹 배치(RankingService)가 지키는 불변식이지, 이 쿼리 자체가
     * 강제하는 건 아니다. 데이터 이상으로 그 불변식이 깨지더라도 여기서 상한을
     * 명시해두면 전체 종목을 수집해버리는 사고를 막는다. 하드코딩하지 않고
     * application.yaml의 trading.universe-size(다른 곳에선 아직 안 쓰이던 설정값)를
     * 그대로 재사용한다. */
    private final int universeSize;

    public MinuteCandleCollectionService(
            MarketSessionProvider marketSessionProvider,
            StockRepository stockRepository,
            MarketDataPort marketDataPort,
            MinuteCandlePersistenceService persistenceService,
            Clock clock,
            @Value("${trading.universe-size}") int universeSize
    ) {
        this.marketSessionProvider = marketSessionProvider;
        this.stockRepository = stockRepository;
        this.marketDataPort = marketDataPort;
        this.persistenceService = persistenceService;
        this.clock = clock;
        this.universeSize = universeSize;
    }

    /**
     * 정규장이 열린 시장만 골라 그 시장의 상위 종목을 수집한다. KR/US 정규장은
     * 겹치지 않으므로(docs/api-spec.md) 어느 순간이든 최대 한 시장만 실제로
     * 수집이 일어난다.
     */
    public void collectOpenMarkets() {
        for (MarketCountry marketCountry : MarketCountry.values()) {
            if (marketSessionProvider.isOpen(marketCountry, clock.instant())) {
                collectMarket(marketCountry);
            }
        }
    }

    private void collectMarket(MarketCountry marketCountry) {
        List<Stock> stocks = stockRepository.findRankedByMarketCountry(
                marketCountry, PageRequest.of(0, universeSize));
        if (stocks.isEmpty()) {
            // 로컬/테스트 환경처럼 랭킹 배치를 아직 안 돌린 경우 정상적으로 발생한다 —
            // 경고감은 아니라 DEBUG로 남긴다.
            log.debug("[{}] 상위 종목이 없어 1분봉 수집을 건너뜁니다.", marketCountry);
            return;
        }

        Pacer pacer = Pacer.forTps(CANDLE_TPS);
        int failureCount = 0;
        for (Stock stock : stocks) {
            try {
                collectOne(stock);
            } catch (RuntimeException exception) {
                // 한 종목이 실패해도(통화 불일치, Toss 일시 오류 등) 나머지 종목은
                // 계속 수집해야 한다 — 온디맨드 단건 조회와 다르게 이건 배치다.
                failureCount++;
                log.warn("[{}] {} 1분봉 수집 실패", marketCountry, stock.getSymbol(), exception);
            } finally {
                pacer.pace();
            }
        }
        // 전량 실패는 종목 한둘의 문제가 아니라 Toss 인증·네트워크 등 어댑터 자체의
        // 장애일 가능성이 높다 — 모니터링(Sentry/Slack 등)이 걸려 있다면 잡아낼 수
        // 있도록 WARN으로 올린다. 부분 실패는 배치의 정상 동작 범위라 INFO로 남긴다.
        if (failureCount == stocks.size()) {
            log.warn("[{}] 1분봉 수집 전량 실패 — {}종목 모두 실패. 어댑터 장애 가능성.", marketCountry, stocks.size());
        } else {
            log.info("[{}] 1분봉 수집 완료 — {}종목 중 {}건 실패", marketCountry, stocks.size(), failureCount);
        }
    }

    private void collectOne(Stock stock) {
        List<Candle> candles = marketDataPort.fetchCandles(stock.getSymbol(), CandleInterval.ONE_MINUTE, FETCH_COUNT);
        CandleCurrencyValidator.validate(stock, candles);
        persistenceService.upsert(stock.getStockId(), candles);
    }
}
