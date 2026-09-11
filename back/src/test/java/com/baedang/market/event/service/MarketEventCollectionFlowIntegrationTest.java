package com.baedang.market.event.service;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventSource;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.model.KindRssBatch;
import com.baedang.market.event.model.MarketEventCandidate;
import com.baedang.market.event.port.MarketEventSourcePort;
import com.baedang.market.event.repository.MarketEventRepository;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketSessionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * KIND 호출은 DB 트랜잭션 밖에서 실행되고 저장만 짧은 트랜잭션으로 커밋된다.
 *
 * <p>이 계약은 어노테이션으로만 표현되므로 순수 단위 테스트로는 검증할 수 없다 —
 * 프록시가 없는 객체는 {@code Propagation.NEVER}를 강제하지 않는다. 실제 빈을 띄워
 * 외부 호출 시점의 트랜잭션 활성 여부를 관측하고, 행이 실제로 커밋되는지 본다.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "krx.market-events.enabled=true",
        "logging.level.org.hibernate.SQL=OFF"
})
class MarketEventCollectionFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    private static final String ACPT_NO = "20260713000658";
    private static final Instant TRIGGERED_AT = Instant.parse("2026-07-13T04:28:32Z");
    private static final Instant HALT_UNTIL = Instant.parse("2026-07-13T04:48:32Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-13T04:29:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-13T04:29:07Z");
    private static final URI SOURCE_URL =
            URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");

    /** toss가 꺼진 컨텍스트에서도 캘린더 Port 체인이 만들어지도록 대체한다. */
    @MockitoBean MarketCalendarPort marketCalendarPort;
    @MockitoBean MarketEventSourcePort source;
    @MockitoBean MarketEventTimingPolicy timing;

    /**
     * 이 컨텍스트는 {@code krx.market-events.enabled=true}라 스케줄러 빈이 함께 등록된다.
     * 장이 닫힌 것으로 고정해 스케줄러가 이 테스트의 명시적 {@code collect()}와 경합하지 않게 한다.
     * (스텁하지 않은 mock의 {@code isOpen}은 false를 돌려준다 — 캘린더가 null이라 NPE로 우연히
     * 건너뛰는 것에 기대지 않는다.)
     */
    @MockitoBean MarketSessionProvider marketSessionProvider;

    @Autowired MarketEventCollectionService collectionService;
    @Autowired MarketEventPersistenceService persistenceService;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MarketEventRepository repositorySpy;
    @Autowired MarketEventRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM market_event");
    }

    @Test
    void external_calls_run_without_a_transaction_and_the_event_commits() {
        MarketEventCandidate candidate = candidate();
        ConfirmedMarketEvent confirmed = confirmed();
        List<Boolean> txActiveDuringFetch = Collections.synchronizedList(new ArrayList<>());

        when(source.fetchCandidates(KrMarket.KOSPI)).thenAnswer(invocation -> {
            txActiveDuringFetch.add(TransactionSynchronizationManager.isActualTransactionActive());
            return new KindRssBatch(List.of(candidate), 0);
        });
        when(source.fetchConfirmed(candidate)).thenAnswer(invocation -> {
            txActiveDuringFetch.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(confirmed);
        });
        when(source.fetchCandidates(KrMarket.KOSDAQ)).thenReturn(new KindRssBatch(List.of(), 0));
        when(timing.haltUntil(confirmed)).thenReturn(HALT_UNTIL);

        collectionService.collect();

        assertThat(txActiveDuringFetch)
                .hasSize(2)
                .containsOnly(false);
        assertThat(storedRowCount()).isEqualTo(1);
        assertThat(repository.existsBySourceAndSourceEventId(MarketEventSource.KRX_KIND, ACPT_NO))
                .isTrue();

        // 저장된 행이 어느 시각을 어느 컬럼에 담았는지 — 발동시각은 상세 공시 값이어야 하고,
        // 게시시각(pubDate)으로 대체되면 활성 구간 [triggeredAt, haltUntil)이 그대로 틀어진다.
        assertThat(instantOf("triggered_at")).isEqualTo(TRIGGERED_AT);
        assertThat(instantOf("halt_until")).isEqualTo(HALT_UNTIL);
        assertThat(instantOf("published_at")).isEqualTo(PUBLISHED_AT);
        assertThat(instantOf("received_at")).isEqualTo(RECEIVED_AT);
    }

    /**
     * 두 인스턴스가 동시에 사전 확인을 통과하는 경합을 재현한다. 사전 확인 두 번을 빗나가게 하고
     * 실제 INSERT는 그대로 두어, DB 제약 위반이 서비스 경계를 넘어 정상 중복으로 흡수되는지 본다.
     *
     * <p>제약 위반 뒤의 재확인만 스텁한다(Spring Data 리포지토리는 인터페이스 프록시라
     * {@code callRealMethod()}를 쓸 수 없다). 그 행이 실제로 존재하는지는 JDBC로 따로 확인한다.
     * 이 테스트가 없으면 "중복 분기가 운영에서 실제로 동작하는가"를 아무도 검증하지 않는다.
     */
    @Test
    void duplicate_race_is_absorbed_as_benign_duplicate() {
        MarketEventCandidate candidate = candidate();
        ConfirmedMarketEvent confirmed = confirmed();
        repository.saveAndFlush(entity(candidate, confirmed));

        doReturn(false, false, true)
                .when(repositorySpy)
                .existsBySourceAndSourceEventId(any(), any());

        when(source.fetchCandidates(KrMarket.KOSPI)).thenReturn(new KindRssBatch(List.of(candidate), 0));
        when(source.fetchCandidates(KrMarket.KOSDAQ)).thenReturn(new KindRssBatch(List.of(), 0));
        when(source.fetchConfirmed(candidate)).thenReturn(Optional.of(confirmed));
        when(timing.haltUntil(confirmed)).thenReturn(HALT_UNTIL);

        assertThatCode(() -> collectionService.collect()).doesNotThrowAnyException();

        assertThat(storedRowCount()).isEqualTo(1);
    }

    @Test
    void collect_refuses_to_run_inside_an_existing_transaction() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.execute(status -> {
            collectionService.collect();
            return null;
        })).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(storedRowCount()).isZero();
    }

    private int storedRowCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM market_event WHERE source_event_id = ?", Integer.class, ACPT_NO);
    }

    private Instant instantOf(String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM market_event WHERE source_event_id = ?",
                java.sql.Timestamp.class, ACPT_NO).toInstant();
    }

    private com.baedang.market.event.entity.MarketEvent entity(
            MarketEventCandidate candidate, ConfirmedMarketEvent confirmed) {
        return com.baedang.market.event.entity.MarketEvent.circuitBreaker(
                com.baedang.market.event.entity.MarketEventSource.KRX_KIND,
                candidate.sourceEventId(),
                candidate.market(),
                candidate.circuitBreakerStage(),
                confirmed.triggeredAt(),
                HALT_UNTIL,
                confirmed.publishedAt(),
                confirmed.receivedAt(),
                candidate.title(),
                SOURCE_URL);
    }

    private MarketEventCandidate candidate() {
        return new MarketEventCandidate(
                KrMarket.KOSPI,
                ACPT_NO,
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                PUBLISHED_AT,
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo=" + ACPT_NO));
    }

    private ConfirmedMarketEvent confirmed() {
        return new ConfirmedMarketEvent(
                ACPT_NO,
                KrMarket.KOSPI,
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                TRIGGERED_AT,
                PUBLISHED_AT,
                RECEIVED_AT,
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                SOURCE_URL);
    }
}
