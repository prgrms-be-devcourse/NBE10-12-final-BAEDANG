package com.baedang.market.event.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEvent;
import com.baedang.market.event.entity.MarketEventSource;
import com.baedang.market.event.model.ActiveMarketHalt;
import com.baedang.market.event.repository.MarketEventRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB 판정은 KR 시장 중 KOSPI·KOSDAQ에만 적용된다. 사이드카는 프로그램 호가만 정지하므로 차단에 쓰지
 * 않는다. 계좌 잠금 뒤 호출되므로 활성 여부를 메모리에 캐시하지 않고 매번 DB에서 읽는다.
 */
class MarketTradingHaltPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-13T04:35:00Z");
    private static final URI SOURCE_URL =
            URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");

    private final MarketEventRepository repository = mock(MarketEventRepository.class);

    private MarketTradingHaltPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new MarketTradingHaltPolicy(repository);
    }

    @Test
    void returns_active_cb_for_kospi_but_never_us_market_or_etc() {
        when(repository.findActiveCircuitBreaker(KrMarket.KOSPI, NOW)).thenReturn(Optional.of(cb()));

        assertThat(policy.activeFor(kospiStock(), NOW)).isPresent();
        assertThat(policy.activeFor(usStock(), NOW)).isEmpty();
        assertThat(policy.activeFor(etcStock(), NOW)).isEmpty();
        verify(repository, never()).findActiveCircuitBreaker(KrMarket.KOSDAQ, NOW);
    }

    @Test
    void returns_kosdaq_cb_without_consulting_kospi() {
        when(repository.findActiveCircuitBreaker(KrMarket.KOSDAQ, NOW)).thenReturn(Optional.of(cb()));

        assertThat(policy.activeFor(kosdaqStock(), NOW)).isPresent();
        verify(repository, never()).findActiveCircuitBreaker(KrMarket.KOSPI, NOW);
    }

    @Test
    void absent_active_cb_yields_empty() {
        when(repository.findActiveCircuitBreaker(KrMarket.KOSPI, NOW)).thenReturn(Optional.empty());

        assertThat(policy.activeFor(kospiStock(), NOW)).isEmpty();
    }

    @Test
    void restores_recorded_cb_for_matching_market() {
        when(repository.findById(99L)).thenReturn(Optional.of(cb()));

        ActiveMarketHalt restored = policy.restoreRecordedHalt(99L, kospiStock());

        assertThat(restored.eventId()).isEqualTo(99L);
        assertThat(restored.market()).isEqualTo(KrMarket.KOSPI);
    }

    @Test
    void rejects_recorded_cb_for_different_market() {
        when(repository.findById(99L)).thenReturn(Optional.of(cb()));

        assertThatThrownBy(() -> policy.restoreRecordedHalt(99L, kosdaqStock()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void error_data_uses_public_contract_fields_but_hides_internal_event_id() {
        MarketEvent stored = cb();
        ActiveMarketHalt halt = ActiveMarketHalt.from(stored);

        assertThat(halt.eventId()).isEqualTo(stored.getMarketEventId());
        assertThat(halt.asErrorData())
                .containsEntry("market", "KOSPI")
                .containsEntry("eventType", "CIRCUIT_BREAKER")
                .containsEntry("stage", 1)
                .containsEntry("triggeredAt", OffsetDateTime.parse("2026-07-13T13:28:32+09:00"))
                .containsEntry("haltUntil", OffsetDateTime.parse("2026-07-13T13:48:32+09:00"))
                .doesNotContainKey("eventId");
    }

    /** 저장되지 않은 이벤트로 만든 거절은 감사 연결이 끊긴다. 재생 계약이 성립하지 않으므로 거부한다. */
    @Test
    void unpersisted_event_without_id_is_rejected() {
        MarketEvent unpersisted = marketEvent();

        assertThatThrownBy(() -> ActiveMarketHalt.from(unpersisted))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void require_trading_allowed_throws_halted_with_event_data() {
        when(repository.findActiveCircuitBreaker(KrMarket.KOSPI, NOW)).thenReturn(Optional.of(cb()));

        assertThatThrownBy(() -> policy.requireTradingAllowed(kospiStock(), NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MARKET_TRADING_HALTED);
                    assertThat(exception.getData())
                            .containsEntry("market", "KOSPI")
                            .containsEntry("stage", 1);
                });
    }

    @Test
    void require_trading_allowed_passes_when_no_cb_is_active() {
        when(repository.findActiveCircuitBreaker(KrMarket.KOSPI, NOW)).thenReturn(Optional.empty());

        policy.requireTradingAllowed(kospiStock(), NOW);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** 사이드카는 거래 차단 사유가 아니다. 활성 CB 조회는 하되 사이드카 이벤트는 사용하지 않는다. */
    @Test
    void sidecar_only_market_is_not_blocked() {
        when(repository.findActiveCircuitBreaker(KrMarket.KOSPI, NOW)).thenReturn(Optional.empty());

        assertThat(policy.activeFor(kospiStock(), NOW)).isEmpty();
        verify(repository).findActiveCircuitBreaker(KrMarket.KOSPI, NOW);
    }

    private Stock kospiStock() {
        return Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
    }

    private Stock kosdaqStock() {
        return Stock.create("247540", MarketCountry.KR, "KOSDAQ", "에코프로비엠", null, "KRW", "STOCK", true);
    }

    private Stock etcStock() {
        return Stock.create("123456", MarketCountry.KR, "KR_ETC", "기타종목", null, "KRW", "STOCK", true);
    }

    private Stock usStock() {
        return Stock.create("AAPL", MarketCountry.US, "NASDAQ", "Apple", null, "USD", "STOCK", true);
    }

    /** DB에 저장된 CB 이벤트를 가정한다. 감사 FK가 걸리려면 identity가 있어야 한다. */
    private MarketEvent cb() {
        MarketEvent event = marketEvent();
        ReflectionTestUtils.setField(event, "marketEventId", 99L);
        return event;
    }

    private MarketEvent marketEvent() {
        return MarketEvent.circuitBreaker(
                MarketEventSource.KRX_KIND, "20260713000658", KrMarket.KOSPI, 1,
                Instant.parse("2026-07-13T04:28:32Z"), Instant.parse("2026-07-13T04:48:32Z"),
                Instant.parse("2026-07-13T04:29:00Z"), Instant.parse("2026-07-13T04:29:07Z"),
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)", SOURCE_URL);
    }
}
