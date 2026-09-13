package com.baedang.market.event.service;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventSource;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.event.repository.MarketEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 확정 이벤트를 엔티티로 옮길 때 <b>어느 시각이 어느 컬럼으로 가는지</b>가 이 클래스의 핵심 책임이다.
 *
 * <p>네 시각은 서로 다른 사건이다 — {@code triggeredAt}은 KRX 상세 공시의 실제 발동시각,
 * {@code publishedAt}은 RSS 게시시각, {@code receivedAt}은 우리가 확인한 시각, {@code haltUntil}은
 * 정책이 계산한 종료시각. 그래서 테스트는 네 값에 <b>모두 다른 값</b>을 넣고 각 컬럼을 확인한다.
 * 두 값이 같으면 뒤바뀌어도 통과한다.
 *
 * <p>특히 {@code publishedAt}으로 {@code triggeredAt}을 대체하면 안 된다. 활성 구간이
 * {@code [triggeredAt, haltUntil)}이라 발동시각이 틀리면 차단 구간이 그대로 틀어진다.
 */
class MarketEventPersistenceServiceTest {

    private static final Instant TRIGGERED_AT = Instant.parse("2026-07-13T04:28:32Z");
    private static final Instant HALT_UNTIL = Instant.parse("2026-07-13T04:48:32Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-13T04:29:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-13T04:29:07Z");
    private static final URI SOURCE_URL =
            URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");
    private static final String ACPT_NO = "20260713000658";

    private final MarketEventRepository repository = mock(MarketEventRepository.class);

    private MarketEventPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new MarketEventPersistenceService(repository);
    }

    @Test
    void circuit_breaker_maps_every_timestamp_to_its_own_column() {
        when(repository.existsBySourceAndSourceEventId(MarketEventSource.KRX_KIND, ACPT_NO))
                .thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.insert(circuitBreaker(), HALT_UNTIL)).isTrue();

        MarketEvent saved = captured();
        assertThat(saved.getTriggeredAt().toInstant()).isEqualTo(TRIGGERED_AT);
        assertThat(saved.getHaltUntil().toInstant()).isEqualTo(HALT_UNTIL);
        assertThat(saved.getPublishedAt().toInstant()).isEqualTo(PUBLISHED_AT);
        assertThat(saved.getReceivedAt().toInstant()).isEqualTo(RECEIVED_AT);
        assertThat(saved.getEventType()).isEqualTo(MarketEventType.CIRCUIT_BREAKER);
        assertThat(saved.getCircuitBreakerStage()).isEqualTo((short) 1);
        assertThat(saved.getSidecarDirection()).isNull();
        assertThat(saved.getMarket()).isEqualTo(KrMarket.KOSPI);
        assertThat(saved.getSource()).isEqualTo(MarketEventSource.KRX_KIND);
        assertThat(saved.getTitle()).isEqualTo("유가증권시장 매매거래 일시중단(1단계 CB 발동)");
        assertThat(saved.getSourceUrl()).isEqualTo(SOURCE_URL);
    }

    @Test
    void sidecar_maps_direction_and_keeps_stage_null() {
        when(repository.existsBySourceAndSourceEventId(MarketEventSource.KRX_KIND, ACPT_NO))
                .thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ConfirmedMarketEvent sidecar = new ConfirmedMarketEvent(
                ACPT_NO,
                KrMarket.KOSDAQ,
                MarketEventType.SIDECAR,
                null,
                SidecarDirection.SELL,
                TRIGGERED_AT,
                PUBLISHED_AT,
                RECEIVED_AT,
                "코스닥시장 프로그램매도호가 일시효력정지(Sidecar 발동)",
                SOURCE_URL);

        assertThat(service.insert(sidecar, HALT_UNTIL)).isTrue();

        MarketEvent saved = captured();
        assertThat(saved.getEventType()).isEqualTo(MarketEventType.SIDECAR);
        assertThat(saved.getSidecarDirection()).isEqualTo(SidecarDirection.SELL);
        assertThat(saved.getCircuitBreakerStage()).isNull();
        assertThat(saved.getMarket()).isEqualTo(KrMarket.KOSDAQ);
        assertThat(saved.getTriggeredAt().toInstant()).isEqualTo(TRIGGERED_AT);
    }

    @Test
    void already_stored_event_is_skipped_without_writing() {
        when(repository.existsBySourceAndSourceEventId(MarketEventSource.KRX_KIND, ACPT_NO))
                .thenReturn(true);

        assertThat(service.insert(circuitBreaker(), HALT_UNTIL)).isFalse();

        verify(repository, never()).saveAndFlush(any());
    }

    private MarketEvent captured() {
        ArgumentCaptor<MarketEvent> captor = ArgumentCaptor.forClass(MarketEvent.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private ConfirmedMarketEvent circuitBreaker() {
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
