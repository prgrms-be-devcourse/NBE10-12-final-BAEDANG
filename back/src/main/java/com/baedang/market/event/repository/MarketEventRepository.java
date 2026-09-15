package com.baedang.market.event.repository;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** 시장조치 사실 이력은 append-only로 저장하므로 삭제·수정 메서드를 노출하지 않는다. */
public interface MarketEventRepository extends Repository<MarketEvent, Long> {

    MarketEvent save(MarketEvent event);

    MarketEvent saveAndFlush(MarketEvent event);

    Optional<MarketEvent> findById(Long marketEventId);
    boolean existsBySourceAndSourceEventId(MarketEventSource source, String sourceEventId);

    @Query("""
            select e from MarketEvent e
            where e.market = :market
              and e.eventType = com.baedang.market.event.entity.MarketEventType.CIRCUIT_BREAKER
              and e.triggeredAt <= :at
              and e.haltUntil > :at
            order by e.haltUntil desc, e.marketEventId desc
            """)
    List<MarketEvent> findActiveCircuitBreakers(
            @Param("market") KrMarket market,
            @Param("at") OffsetDateTime at,
            Pageable pageable);

    default Optional<MarketEvent> findActiveCircuitBreaker(KrMarket market, Instant at) {
        return findActiveCircuitBreakers(
                market,
                at.atOffset(ZoneOffset.UTC),
                PageRequest.of(0, 1)
        ).stream().findFirst();
    }

    @Query("""
            select e from MarketEvent e
            where e.market = :market
              and e.triggeredAt >= :from
              and e.triggeredAt < :to
            order by e.triggeredAt desc, e.marketEventId desc
            """)
    List<MarketEvent> findHistory(
            @Param("market") KrMarket market,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            Pageable pageable);
}
