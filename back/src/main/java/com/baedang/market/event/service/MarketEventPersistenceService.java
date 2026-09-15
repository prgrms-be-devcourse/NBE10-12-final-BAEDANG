package com.baedang.market.event.service;

import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventSource;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.repository.MarketEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * 확정된 시장조치 하나를 append-only로 저장한다.
 *
 * <p>수집 서비스는 {@code Propagation.NEVER}로 외부 호출을 트랜잭션 밖에 둔다. 저장만 여기서
 * 짧은 REQUIRED 트랜잭션이 되므로, KIND 응답을 기다리는 동안 DB 커넥션을 붙들지 않는다.
 *
 * <p>저장 전 존재 확인은 낙관적 최적화일 뿐이다. 두 인스턴스가 동시에 조회하면 둘 다 통과할 수
 * 있고, 그때 중복을 실제로 막는 것은 {@code uq_market_event_source}다. 그 충돌은 여기서 삼키지 않고
 * 호출자에게 전파한다 — 호출자만이 그것이 정상 중복인지 아닌지 판단할 수 있다.
 */
@Service
public class MarketEventPersistenceService {

    private static final MarketEventSource SOURCE = MarketEventSource.KRX_KIND;

    private final MarketEventRepository repository;

    public MarketEventPersistenceService(MarketEventRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * @return 저장했으면 {@code true}, 같은 {@code (source, sourceEventId)}가 이미 있어 건너뛰었으면 {@code false}
     */
    @Transactional
    public boolean insert(ConfirmedMarketEvent source, Instant haltUntil) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(haltUntil, "haltUntil must not be null");

        if (repository.existsBySourceAndSourceEventId(SOURCE, source.sourceEventId())) {
            return false;
        }
        repository.saveAndFlush(toEntity(source, haltUntil));
        return true;
    }

    private MarketEvent toEntity(ConfirmedMarketEvent source, Instant haltUntil) {
        if (source.eventType() == MarketEventType.CIRCUIT_BREAKER) {
            return MarketEvent.circuitBreaker(
                    SOURCE,
                    source.sourceEventId(),
                    source.market(),
                    source.circuitBreakerStage(),
                    source.triggeredAt(),
                    haltUntil,
                    source.publishedAt(),
                    source.receivedAt(),
                    source.title(),
                    source.sourceUrl());
        }
        return MarketEvent.sidecar(
                SOURCE,
                source.sourceEventId(),
                source.market(),
                source.sidecarDirection(),
                source.triggeredAt(),
                haltUntil,
                source.publishedAt(),
                source.receivedAt(),
                source.title(),
                source.sourceUrl());
    }
}
