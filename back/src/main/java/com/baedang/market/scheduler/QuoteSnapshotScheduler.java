package com.baedang.market.scheduler;

import com.baedang.global.metrics.TradingMetrics;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.market.service.QuoteSnapshotLoadService;
import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 정규장 중 랭킹·활성 지정가 주문 종목의 현재가 배치를 제출합니다.
 *
 * <p>장이 닫히면 수집을 건너뛰고 {@code quote_snapshot.last_price}에 마지막 체결가가
 * 그대로 보존되어 자연스럽게 "전일 종가" 역할을 수행합니다 (docs/erd.md).
 *
 * <p>{@code toss.enabled=true}일 때만 스케줄러가 동작합니다.
 */
@Component
@ConditionalOnProperty(prefix = "toss", name = "enabled", havingValue = "true")
public class QuoteSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuoteSnapshotScheduler.class);

    private final QuoteSnapshotLoadService quoteSnapshotLoadService;
    private final MarketSessionProvider marketSessionProvider;
    private final Clock clock;
    private final TradingMetrics metrics;
    private boolean usFirst;

    public QuoteSnapshotScheduler(
            QuoteSnapshotLoadService quoteSnapshotLoadService,
            MarketSessionProvider marketSessionProvider, Clock clock,
            TradingMetrics metrics
    ) {
        this.quoteSnapshotLoadService = quoteSnapshotLoadService;
        this.marketSessionProvider = marketSessionProvider;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${trading.quote-collection.dispatch-interval:25ms}",
            scheduler = "quoteCollectionScheduler")
    public void pollQuotes() {
        Instant now = clock.instant();

        MarketCountry first = usFirst ? MarketCountry.US : MarketCountry.KR;
        MarketCountry second = usFirst ? MarketCountry.KR : MarketCountry.US;
        if (pollIfMarketOpen(first, now)) usFirst = first != MarketCountry.US;
        if (pollIfMarketOpen(second, now)) usFirst = second != MarketCountry.US;
    }

    private boolean pollIfMarketOpen(MarketCountry marketCountry, Instant now) {
        try {
            MarketSessionStatus session = marketSessionProvider.currentSession(marketCountry, now);
            // 매 tick 개장 여부를 게이지로 갱신한다 — QuoteStale 알림이 이 값과 조인해
            // 휴장 시장의 staleness 증가를 무시하도록(야간·주말 오탐 방지).
            // !! 시세 신선도(quoteUpdated)는 여기서 기록하지 않는다. syncQuotes>0 은 "비동기 수집
            //    대상으로 제출했다"는 뜻일 뿐, 실제 Toss 조회·저장은 이후 executor 에서 일어나기 때문이다.
            //    제출 시점에 신선도를 초기화하면 조회/저장이 계속 실패해도 정상처럼 보인다(false green).
            //    실제 저장 성공 시점에 QuoteRefreshCoordinator.fetch() 가 quoteUpdated 를 기록한다.
            metrics.marketOpen(marketCountry.name(), session.open());
            if (session.open()) return quoteSnapshotLoadService.syncQuotes(marketCountry, session.validUntil()) > 0;
            else log.trace("장 휴장 상태로 시세 수집 건너뜀: marketCountry={}", marketCountry);
        } catch (Exception e) {
            log.error("시세 수집 스케줄러 실행 중 오류 발생: marketCountry={}", marketCountry, e);
        }
        return false;
    }
}
