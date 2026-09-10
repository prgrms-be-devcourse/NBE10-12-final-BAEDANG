package com.baedang.market.event.repository;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventSource;
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
        MarketEvent reloaded = repository.findById(kosdaq.getMarketEventId()).orElseThrow();
        assertThat(reloaded.getTriggeredAt().toInstant()).isEqualTo(START);
        assertThat(reloaded.getHaltUntil().toInstant()).isEqualTo(END);
        assertThat(reloaded.getPublishedAt().toInstant()).isEqualTo(PUBLISHED_AT);
        assertThat(reloaded.getReceivedAt().toInstant()).isEqualTo(RECEIVED_AT);
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
