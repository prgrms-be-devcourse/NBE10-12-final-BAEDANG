package com.baedang.market.event.scheduler;

import com.baedang.market.event.service.MarketEventCollectionService;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * KR 정규장 중에만 KIND 시장조치 수집을 주기 실행한다.
 *
 * <p>장외에는 KIND를 호출하지 않는다 — 공시가 없을 뿐 아니라, 호출 자체가 낭비이고 피드가
 * 장 마감 후 정리되면 오래된 항목을 다시 볼 이유도 없다.
 *
 * <p>주기 실행과 시작 복구 모두 전용 단일 스레드({@code marketEventTaskScheduler})를 쓴다.
 * 같은 인스턴스에서 수집이 겹치지 않고, KIND 지연이 다른 배치를 밀지 않는다.
 *
 * <p>세션 조회나 수집이 실패해도 예외를 밖으로 내보내지 않는다. 스케줄러 스레드에서 예외가
 * 새면 다음 주기 실행이 통째로 멈출 수 있다.
 */
@Component
@ConditionalOnProperty(prefix = "krx.market-events", name = "enabled", havingValue = "true")
public class MarketEventCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketEventCollectionScheduler.class);

    private final MarketEventCollectionService collection;
    private final MarketSessionProvider sessions;
    private final Clock clock;
    private final ThreadPoolTaskScheduler scheduler;

    public MarketEventCollectionScheduler(
            MarketEventCollectionService collection,
            MarketSessionProvider sessions,
            Clock clock,
            @Qualifier("marketEventTaskScheduler") ThreadPoolTaskScheduler scheduler
    ) {
        this.collection = Objects.requireNonNull(collection, "collection must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    /** fixedDelay라 이전 실행이 끝난 뒤 다음 주기를 센다. KIND가 느려도 수집이 겹치지 않는다. */
    @Scheduled(
            fixedDelayString = "${krx.market-events.poll-interval:15s}",
            scheduler = "marketEventTaskScheduler")
    public void poll() {
        collectIfOpen();
    }

    /** 기동 직후 한 번 시도한다. 첫 주기를 기다리지 않고 장중 재시작을 복구한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        scheduler.execute(this::collectIfOpen);
    }

    private void collectIfOpen() {
        Instant now = clock.instant();
        try {
            if (!sessions.isOpen(MarketCountry.KR, now)) {
                return;
            }
        } catch (RuntimeException e) {
            // 세션을 모르면 수집하지 않는다. 추측해서 호출하면 장외 호출이 된다.
            log.warn("시장 세션 조회 실패로 수집을 건너뜁니다: cause={}", e.toString());
            return;
        }

        try {
            collection.collect();
        } catch (RuntimeException e) {
            log.warn("시장조치 수집 실패: cause={}", e.toString());
        }
    }
}
