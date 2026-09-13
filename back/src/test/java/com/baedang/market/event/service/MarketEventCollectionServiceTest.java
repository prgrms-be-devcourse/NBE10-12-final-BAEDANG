package com.baedang.market.event.service;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventSource;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.model.KindRssBatch;
import com.baedang.market.event.model.MarketEventCandidate;
import com.baedang.market.event.port.MarketEventSourcePort;
import com.baedang.market.event.repository.MarketEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 수집은 외부 호출이 실패해도 이미 저장된 이벤트의 {@code halt_until}을 건드리지 않는다.
 * 한 후보·한 시장의 실패가 나머지 수집을 막지 않고, 중복은 예외가 아니라 정상 중복으로 흡수된다.
 */
class MarketEventCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-13T04:35:00Z");
    private static final URI SOURCE_URL =
            URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");

    private final MarketEventSourcePort source = mock(MarketEventSourcePort.class);
    private final MarketEventTimingPolicy timing = mock(MarketEventTimingPolicy.class);
    private final MarketEventPersistenceService persistence = mock(MarketEventPersistenceService.class);
    private final MarketEventRepository repository = mock(MarketEventRepository.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private MarketEventCollectionService service;

    @BeforeEach
    void setUp() {
        // 기본은 두 시장 모두 후보 없음. 필요한 시장만 각 테스트가 덮어쓴다.
        when(source.fetchCandidates(any())).thenReturn(new KindRssBatch(List.of(), 0));
        service = new MarketEventCollectionService(
                source,
                timing,
                persistence,
                repository,
                metrics);
    }

    @Test
    void fetches_detail_only_for_new_source_ids_and_persists_late_events_as_history() {
        MarketEventCandidate existing = candidate(KrMarket.KOSPI, "20260713000658");
        MarketEventCandidate late = candidate(KrMarket.KOSPI, "20260713000659");
        ConfirmedMarketEvent confirmedLate = confirmed(late, NOW.minusSeconds(1800));

        when(source.fetchCandidates(KrMarket.KOSPI)).thenReturn(batch(existing, late));
        when(repository.existsBySourceAndSourceEventId(MarketEventSource.KRX_KIND, "20260713000658"))
                .thenReturn(true);
        when(repository.existsBySourceAndSourceEventId(MarketEventSource.KRX_KIND, "20260713000659"))
                .thenReturn(false);
        when(source.fetchConfirmed(late)).thenReturn(Optional.of(confirmedLate));
        when(timing.haltUntil(confirmedLate)).thenReturn(NOW.minusSeconds(1));

        service.collect();

        verify(source, never()).fetchConfirmed(existing);
        verify(persistence).insert(confirmedLate, NOW.minusSeconds(1));
    }

    @Test
    void one_candidate_failure_does_not_stop_the_next_candidate() {
        MarketEventCandidate broken = candidate(KrMarket.KOSPI, "20260713000660");
        MarketEventCandidate valid = candidate(KrMarket.KOSPI, "20260713000661");
        ConfirmedMarketEvent confirmedValid = confirmed(valid, NOW.minusSeconds(60));

        when(source.fetchCandidates(KrMarket.KOSPI)).thenReturn(batch(broken, valid));
        when(source.fetchConfirmed(broken)).thenThrow(new IllegalStateException("detail fetch failed"));
        when(source.fetchConfirmed(valid)).thenReturn(Optional.of(confirmedValid));
        when(timing.haltUntil(confirmedValid)).thenReturn(NOW.plusSeconds(300));

        service.collect();

        verify(persistence).insert(confirmedValid, NOW.plusSeconds(300));
    }

    @Test
    void attempts_every_market_when_one_feed_fails() {
        when(source.fetchCandidates(KrMarket.KOSPI))
                .thenThrow(new IllegalStateException("rss unavailable"));

        service.collect();

        verify(source).fetchCandidates(KrMarket.KOSPI);
        verify(source).fetchCandidates(KrMarket.KOSDAQ);
    }

    @Test
    void failed_market_does_not_block_the_other_market_and_touches_no_stored_event() {
        MarketEventCandidate kosdaq = candidate(KrMarket.KOSDAQ, "20260805000214");
        ConfirmedMarketEvent confirmedKosdaq = confirmed(kosdaq, NOW.minusSeconds(60));

        when(source.fetchCandidates(KrMarket.KOSPI))
                .thenThrow(new IllegalStateException("rss unavailable"));
        when(source.fetchCandidates(KrMarket.KOSDAQ)).thenReturn(batch(kosdaq));
        when(source.fetchConfirmed(kosdaq)).thenReturn(Optional.of(confirmedKosdaq));
        when(timing.haltUntil(confirmedKosdaq)).thenReturn(NOW.plusSeconds(300));

        service.collect();

        verify(persistence).insert(confirmedKosdaq, NOW.plusSeconds(300));
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void unconfirmed_candidate_is_not_persisted() {
        MarketEventCandidate candidate = candidate(KrMarket.KOSPI, "20260713000662");

        when(source.fetchCandidates(KrMarket.KOSPI)).thenReturn(batch(candidate));
        when(source.fetchConfirmed(candidate)).thenReturn(Optional.empty());

        service.collect();

        verifyNoInteractions(persistence);
        verify(timing, never()).haltUntil(any());
        assertThat(counterCount("krx.market_event.parse_error", "stage", "detail")).isEqualTo(1.0);
    }

    /**
     * 캘린더 장애는 전송 장애와 다른 사건이다. 둘을 같은 태그로 묶으면 운영에서 "KIND가 죽었나"와
     * "우리가 종료시각을 못 구했나"를 구별할 수 없다. 후자는 3단계 CB가 차단되지 않는 fail-open이라
     * 반드시 따로 보여야 한다.
     */
    @Test
    void calendar_failure_is_tagged_separately_and_does_not_stop_the_next_candidate() {
        MarketEventCandidate first = candidate(KrMarket.KOSPI, "20260713000670");
        MarketEventCandidate second = candidate(KrMarket.KOSPI, "20260713000671");
        ConfirmedMarketEvent confirmedFirst = confirmed(first, NOW.minusSeconds(60));
        ConfirmedMarketEvent confirmedSecond = confirmed(second, NOW.minusSeconds(60));

        when(source.fetchCandidates(KrMarket.KOSPI)).thenReturn(batch(first, second));
        when(source.fetchConfirmed(first)).thenReturn(Optional.of(confirmedFirst));
        when(source.fetchConfirmed(second)).thenReturn(Optional.of(confirmedSecond));
        when(timing.haltUntil(confirmedFirst))
                .thenThrow(new IllegalStateException("market calendar unavailable"));
        when(timing.haltUntil(confirmedSecond)).thenReturn(NOW.plusSeconds(300));

        service.collect();

        verify(persistence, never()).insert(eq(confirmedFirst), any());
        verify(persistence).insert(confirmedSecond, NOW.plusSeconds(300));
        assertThat(counterCount("krx.market_event.parse_error", "stage", "calendar")).isEqualTo(1.0);
        assertThat(counterCount("krx.market_event.parse_error", "stage", "fetch")).isZero();
    }

    /**
     * DB 장애는 후보 파싱 실패가 아니다. 함께 묶으면 운영에서 "KIND가 죽었나"와 "DB가 죽었나"를
     * 구별할 수 없고, 후보 단위로 격리해 계속 시도하는 것도 의미가 없다 — 다음 후보도 실패한다.
     */
    @Test
    void database_failure_propagates_and_is_not_tagged_as_parse_error() {
        MarketEventCandidate candidate = candidate(KrMarket.KOSPI, "20260713000690");
        when(source.fetchCandidates(KrMarket.KOSPI)).thenReturn(batch(candidate));
        when(repository.existsBySourceAndSourceEventId(MarketEventSource.KRX_KIND, "20260713000690"))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.collect())
                .isInstanceOf(DataAccessException.class);

        assertThat(counterCount("krx.market_event.parse_error", "stage", "fetch")).isZero();
        verifyNoInteractions(persistence);
    }

    @Test
    void batch_parse_errors_do_not_block_valid_candidates() {
        MarketEventCandidate valid = candidate(KrMarket.KOSPI, "20260713000664");
        ConfirmedMarketEvent confirmedValid = confirmed(valid, NOW.minusSeconds(60));

        when(source.fetchCandidates(KrMarket.KOSPI)).thenReturn(new KindRssBatch(List.of(valid), 3));
        when(source.fetchConfirmed(valid)).thenReturn(Optional.of(confirmedValid));
        when(timing.haltUntil(confirmedValid)).thenReturn(NOW.plusSeconds(300));

        service.collect();

        verify(persistence).insert(confirmedValid, NOW.plusSeconds(300));
    }

    @Test
    void unique_race_is_absorbed_as_duplicate_instead_of_failing() {
        MarketEventCandidate race = candidate(KrMarket.KOSPI, "20260713000663");
        ConfirmedMarketEvent confirmedRace = confirmed(race, NOW.minusSeconds(60));

        when(source.fetchCandidates(KrMarket.KOSPI)).thenReturn(batch(race));
        when(repository.existsBySourceAndSourceEventId(MarketEventSource.KRX_KIND, "20260713000663"))
                .thenReturn(false, true);
        when(source.fetchConfirmed(race)).thenReturn(Optional.of(confirmedRace));
        when(timing.haltUntil(confirmedRace)).thenReturn(NOW.plusSeconds(300));
        when(persistence.insert(confirmedRace, NOW.plusSeconds(300)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value"));

        assertThatCode(() -> service.collect()).doesNotThrowAnyException();
    }

    private KindRssBatch batch(MarketEventCandidate... candidates) {
        return new KindRssBatch(List.of(candidates), 0);
    }

    private double counterCount(String name, String tagKey, String tagValue) {
        var counter = metrics.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private MarketEventCandidate candidate(KrMarket market, String sourceEventId) {
        return new MarketEventCandidate(
                market,
                sourceEventId,
                MarketEventType.CIRCUIT_BREAKER,
                1,
                null,
                NOW.minusSeconds(300),
                title(market),
                URI.create("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptNo="
                        + sourceEventId));
    }

    private ConfirmedMarketEvent confirmed(MarketEventCandidate candidate, Instant triggeredAt) {
        return new ConfirmedMarketEvent(
                candidate.sourceEventId(),
                candidate.market(),
                candidate.eventType(),
                candidate.circuitBreakerStage(),
                candidate.sidecarDirection(),
                triggeredAt,
                candidate.publishedAt(),
                NOW,
                candidate.title(),
                SOURCE_URL);
    }

    private static String title(KrMarket market) {
        return market == KrMarket.KOSPI
                ? "유가증권시장 매매거래 일시중단(1단계 CB 발동)"
                : "코스닥시장 매매거래 일시중단(1단계 CB 발동)";
    }
}
