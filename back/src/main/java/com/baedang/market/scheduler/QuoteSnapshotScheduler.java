package com.baedang.market.scheduler;

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
    private boolean usFirst;

    public QuoteSnapshotScheduler(
            QuoteSnapshotLoadService quoteSnapshotLoadService,
            MarketSessionProvider marketSessionProvider, Clock clock
    ) {
        this.quoteSnapshotLoadService = quoteSnapshotLoadService;
        this.marketSessionProvider = marketSessionProvider;
        this.clock = clock;
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
            if (session.open()) return quoteSnapshotLoadService.syncQuotes(marketCountry, session.validUntil()) > 0;
            else log.trace("장 휴장 상태로 시세 수집 건너뜀: marketCountry={}", marketCountry);
        } catch (Exception e) {
            log.error("시세 수집 스케줄러 실행 중 오류 발생: marketCountry={}", marketCountry, e);
        }
        return false;
    }
}
