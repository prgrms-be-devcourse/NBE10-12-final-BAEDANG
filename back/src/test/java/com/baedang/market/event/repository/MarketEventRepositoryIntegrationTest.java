package com.baedang.market.event.repository;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MarketEventRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    private static final Instant START = Instant.parse("2026-07-13T04:28:32Z");
    private static final Instant END = Instant.parse("2026-07-13T04:48:32Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-13T04:29:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-13T04:29:07Z");
    private static final URI SOURCE_URL = URI.create(
            "https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");

    @Autowired
    private MarketEventRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Test
    void halt_until_is_exclusive() {
        MarketEvent event = repository.saveAndFlush(circuitBreaker("20260713000658", START, END));

        assertThat(repository.findActiveCircuitBreaker(
                KrMarket.KOSPI, Instant.parse("2026-07-13T04:48:31Z")))
                .contains(event);
        assertThat(repository.findActiveCircuitBreaker(
                KrMarket.KOSPI, END))
                .isEmpty();
    }

    @Test
    void source_and_source_event_id_are_unique() {
        repository.saveAndFlush(circuitBreaker("20260713000658", START, END));

        assertThatThrownBy(() -> repository.saveAndFlush(
                circuitBreaker("20260713000658", START, END)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void market_isolation_and_utc_round_trip_are_preserved() {
        MarketEvent kosdaq = repository.saveAndFlush(
                MarketEvent.circuitBreaker(MarketEventSource.KRX_KIND, "20260713000664", KrMarket.KOSDAQ,
                        2, START, END, PUBLISHED_AT, RECEIVED_AT, "코스닥 CB", SOURCE_URL));

        assertThat(repository.findActiveCircuitBreaker(KrMarket.KOSPI, START.plusSeconds(1))).isEmpty();
        Long eventId = kosdaq.getMarketEventId();
        entityManager.clear();

        MarketEvent reloaded = repository.findById(eventId).orElseThrow();
        assertThat(reloaded.getTriggeredAt().toInstant()).isEqualTo(START);
        assertThat(reloaded.getHaltUntil().toInstant()).isEqualTo(END);
        assertThat(reloaded.getPublishedAt().toInstant()).isEqualTo(PUBLISHED_AT);
        assertThat(reloaded.getReceivedAt().toInstant()).isEqualTo(RECEIVED_AT);
        assertThat(reloaded.getTriggeredAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(reloaded.getHaltUntil().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(reloaded.getPublishedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(reloaded.getReceivedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void history_is_newest_first_and_respects_limit() {
        MarketEvent older = repository.saveAndFlush(circuitBreaker(
                "20260713000665", START, END));
        MarketEvent newer = repository.saveAndFlush(circuitBreaker(
                "20260713000666", START.plusSeconds(60), END.plusSeconds(60)));

        var history = repository.findHistory(
                KrMarket.KOSPI,
                START.minusSeconds(1).atOffset(java.time.ZoneOffset.UTC),
                END.plusSeconds(61).atOffset(java.time.ZoneOffset.UTC),
                org.springframework.data.domain.PageRequest.of(0, 1));

        assertThat(history).containsExactly(newer);
        assertThat(older.getMarketEventId()).isLessThan(newer.getMarketEventId());
    }

    @Test
    void database_check_constraint_rejects_unknown_market() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO market_event
                    (source, source_event_id, market, event_type, circuit_breaker_stage,
                     triggered_at, halt_until, published_at, received_at, title, source_url)
                VALUES ('KRX_KIND', '20260713000667', 'NYSE', 'CIRCUIT_BREAKER', 1,
                        '2026-07-13T04:28:32Z', '2026-07-13T04:48:32Z',
                        '2026-07-13T04:29:00Z', '2026-07-13T04:29:07Z',
                        '잘못된 시장', 'https://kind.krx.co.kr/event')
                """))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }


    @Test
    void database_has_all_market_event_check_constraints() {
        assertThat(jdbc.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'market_event'::regclass
                  AND contype = 'c'
                """, String.class))
                .contains("ck_market_event_market", "ck_market_event_type",
                        "ck_market_event_time", "ck_market_event_payload");
    }

    @Test
    void database_rejects_unknown_event_type() {
        assertRawInsertRejected("20260713000668", "KOSPI", "UNKNOWN", 1, null,
                "2026-07-13T04:28:32Z", "2026-07-13T04:48:32Z");
    }

    @Test
    void database_rejects_non_increasing_event_time() {
        assertConstraintRejected("ck_market_event_time", "20260713000669", "KOSPI",
                "CIRCUIT_BREAKER", 1, null,
                "2026-07-13T04:48:32Z", "2026-07-13T04:48:32Z");
    }

    @Test
    void database_rejects_circuit_breaker_without_stage() {
        assertConstraintRejected("ck_market_event_payload", "20260713000670", "KOSPI",
                "CIRCUIT_BREAKER", null, null,
                "2026-07-13T04:28:32Z", "2026-07-13T04:48:32Z");
    }

    @Test
    void database_rejects_circuit_breaker_with_sidecar_direction() {
        assertConstraintRejected("ck_market_event_payload", "20260713000671", "KOSPI",
                "CIRCUIT_BREAKER", 1, "BUY",
                "2026-07-13T04:28:32Z", "2026-07-13T04:48:32Z");
    }

    @Test
    void database_rejects_sidecar_with_circuit_breaker_stage() {
        assertConstraintRejected("ck_market_event_payload", "20260713000672", "KOSDAQ",
                "SIDECAR", 1, "SELL",
                "2026-07-13T04:28:32Z", "2026-07-13T04:33:32Z");
    }

    @Test
    void database_rejects_sidecar_without_direction() {
        assertConstraintRejected("ck_market_event_payload", "20260713000673", "KOSDAQ",
                "SIDECAR", null, null,
                "2026-07-13T04:28:32Z", "2026-07-13T04:33:32Z");
    }

    @Test
    void database_rejects_sidecar_with_unknown_direction() {
        assertConstraintRejected("ck_market_event_payload", "20260713000674", "KOSDAQ",
                "SIDECAR", null, "HOLD",
                "2026-07-13T04:28:32Z", "2026-07-13T04:33:32Z");
    }

    private void assertConstraintRejected(
            String constraint,
            String sourceEventId,
            String market,
            String eventType,
            Integer stage,
            String direction,
            String triggeredAt,
            String haltUntil
    ) {
        assertThatThrownBy(() -> insertRaw(
                sourceEventId, market, eventType, stage, direction, triggeredAt, haltUntil))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, exception ->
                        assertThat(exception.getMostSpecificCause().getMessage()).contains(constraint));
    }

    private void assertRawInsertRejected(
            String sourceEventId,
            String market,
            String eventType,
            Integer stage,
            String direction,
            String triggeredAt,
            String haltUntil
    ) {
        assertThatThrownBy(() -> insertRaw(
                sourceEventId, market, eventType, stage, direction, triggeredAt, haltUntil))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRaw(
            String sourceEventId,
            String market,
            String eventType,
            Integer stage,
            String direction,
            String triggeredAt,
            String haltUntil
    ) {
        jdbc.update("""
                INSERT INTO market_event
                    (source, source_event_id, market, event_type, circuit_breaker_stage,
                     sidecar_direction, triggered_at, halt_until, published_at, received_at,
                     title, source_url)
                VALUES ('KRX_KIND', ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ),
                        '2026-07-13T04:29:00Z', '2026-07-13T04:29:07Z',
                        '제약 검증', 'https://kind.krx.co.kr/event')
                """, sourceEventId, market, eventType, stage, direction, triggeredAt, haltUntil);
    }
    private MarketEvent circuitBreaker(String sourceEventId, Instant triggeredAt, Instant haltUntil) {
        return MarketEvent.circuitBreaker(
                MarketEventSource.KRX_KIND,
                sourceEventId,
                KrMarket.KOSPI,
                1,
                triggeredAt,
                haltUntil,
                PUBLISHED_AT,
                RECEIVED_AT,
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                SOURCE_URL);
    }
}
