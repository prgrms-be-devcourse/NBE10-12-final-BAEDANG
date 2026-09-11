package com.baedang.market.event.service;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventSource;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.model.KindRssBatch;
import com.baedang.market.event.model.MarketEventCandidate;
import com.baedang.market.event.port.MarketEventSourcePort;
import com.baedang.market.event.repository.MarketEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * KOSPI·KOSDAQ의 KIND 시장조치 후보를 확인하고 저장한다.
 *
 * <p><b>외부 호출은 트랜잭션 밖에서 끝난다.</b> {@code Propagation.NEVER}가 그 계약을 강제하며,
 * 저장만 {@link MarketEventPersistenceService}의 짧은 트랜잭션으로 분리된다. KIND 응답을 기다리는
 * 동안 DB 커넥션과 거래 잠금을 붙들지 않는다.
 *
 * <p><b>부분 실패는 전체를 막지 않는다.</b> 시장 하나의 RSS 장애는 다른 시장 수집을 막지 않고,
 * 후보 하나의 실패는 다음 후보를 막지 않는다. 실패해도 이미 저장된 이벤트의 {@code halt_until}을
 * 연장하지 않는다 — 차단은 저장된 시각으로만 자동 해제된다.
 *
 * <p>{@code enabled=true}일 때만 빈으로 등록되므로 기본 머지 상태에서는 호출 자체가 없다.
 */
@Service
@ConditionalOnProperty(prefix = "krx.market-events", name = "enabled", havingValue = "true")
@Transactional(propagation = Propagation.NEVER)
public class MarketEventCollectionService {

    private static final Logger log = LoggerFactory.getLogger(MarketEventCollectionService.class);
    private static final MarketEventSource SOURCE = MarketEventSource.KRX_KIND;

    private final MarketEventSourcePort source;
    private final MarketEventTimingPolicy timing;
    private final MarketEventPersistenceService persistence;
    private final MarketEventRepository repository;
    private final MeterRegistry metrics;

    public MarketEventCollectionService(
            MarketEventSourcePort source,
            MarketEventTimingPolicy timing,
            MarketEventPersistenceService persistence,
            MarketEventRepository repository,
            MeterRegistry metrics
    ) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.timing = Objects.requireNonNull(timing, "timing must not be null");
        this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    public void collect() {
        for (KrMarket market : KrMarket.values()) {
            collectMarket(market);
        }
    }

    /** 시장 단위 실패는 그 시장만 건너뛴다. 다음 시장은 계속 수집한다. */
    private void collectMarket(KrMarket market) {
        KindRssBatch batch;
        try {
            batch = source.fetchCandidates(market);
        } catch (RuntimeException e) {
            count("krx.market_event.poll", market.name(), "result", "failure");
            log.warn("KIND RSS 조회 실패: market={}, cause={}", market, e.toString());
            return;
        }
        count("krx.market_event.poll", market.name(), "result", "success");

        if (batch.parseErrorCount() > 0) {
            metrics.counter("krx.market_event.parse_error", "market", market.name(), "stage", "rss")
                    .increment(batch.parseErrorCount());
        }
        for (MarketEventCandidate candidate : batch.candidates()) {
            collectCandidate(market, candidate);
        }
    }

    /** 항목 단위 실패는 그 후보만 건너뛴다. 로그에는 식별자와 실패 지점만 남기고 원문은 남기지 않는다. */
    private void collectCandidate(KrMarket market, MarketEventCandidate candidate) {
        String sourceEventId = candidate.sourceEventId();
        try {
            if (repository.existsBySourceAndSourceEventId(SOURCE, sourceEventId)) {
                return;
            }
            count("krx.market_event.candidate", market.name(), "type", candidate.eventType().name());

            Optional<ConfirmedMarketEvent> confirmed = source.fetchConfirmed(candidate);
            if (confirmed.isEmpty()) {
                count("krx.market_event.parse_error", market.name(), "stage", "detail");
                log.warn("KIND 상세 확인 실패: market={}, acptNo={}, type={}",
                        market, sourceEventId, candidate.eventType());
                return;
            }

            ConfirmedMarketEvent event = confirmed.get();

            Instant haltUntil;
            try {
                haltUntil = timing.haltUntil(event);
            } catch (RuntimeException e) {
                // 시장 캘린더를 신뢰할 수 없으면 이 후보를 저장하지 않는다. 종료시각을 추정해 넣으면
                // append-only 행이 영구히 틀린 값을 갖고, 다음 폴링은 기존 acptNo를 건너뛰어 고칠 기회가 없다.
                count("krx.market_event.parse_error", market.name(), "stage", "calendar");
                log.warn("시장조치 종료시각 계산 실패: market={}, acptNo={}, type={}, cause={}",
                        market, sourceEventId, event.eventType(), e.toString());
                return;
            }

            if (persistence.insert(event, haltUntil)) {
                count("krx.market_event.persisted", market.name(), "type", event.eventType().name());
                metrics.timer("krx.market_event.delivery_delay", "market", market.name(),
                                "type", event.eventType().name())
                        .record(Duration.between(event.triggeredAt(), event.receivedAt()));
            }
        } catch (DataIntegrityViolationException e) {
            if (repository.existsBySourceAndSourceEventId(SOURCE, sourceEventId)) {
                // 다른 인스턴스가 먼저 저장한 정상 중복. 오류가 아니다.
                log.debug("이미 저장된 시장조치: market={}, acptNo={}", market, sourceEventId);
                return;
            }
            // 같은 키가 없는데 제약을 위반했다면 우리 데이터가 스키마와 어긋난 것이다. 중복으로 숨기지 않는다.
            log.error("시장조치 저장 제약 위반: market={}, acptNo={}", market, sourceEventId, e);
            throw e;
        } catch (RuntimeException e) {
            // 전송·파싱 실패는 그 후보만 버린다. 기존 이벤트의 halt_until은 건드리지 않는다.
            count("krx.market_event.parse_error", market.name(), "stage", "fetch");
            log.warn("시장조치 후보 처리 실패: market={}, acptNo={}, cause={}",
                    market, sourceEventId, e.toString());
        }
    }

    /** 태그는 닫힌 열거형 값만 쓴다. 제목·URL·acptNo는 카디널리티가 무한하므로 절대 태그로 쓰지 않는다. */
    private void count(String name, String market, String tagKey, String tagValue) {
        metrics.counter(name, "market", market, tagKey, tagValue).increment();
    }
}
