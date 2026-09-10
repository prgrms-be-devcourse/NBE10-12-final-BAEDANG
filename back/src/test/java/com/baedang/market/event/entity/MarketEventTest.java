package com.baedang.market.event.entity;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketEventTest {

    private static final Instant TRIGGERED_AT = Instant.parse("2026-07-13T04:28:32Z");
    private static final Instant HALT_UNTIL = Instant.parse("2026-07-13T04:48:32Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-13T04:29:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-13T04:29:07Z");
    private static final URI SOURCE_URL = URI.create(
            "https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");

    @Test
    void stock_market_names_are_normalized_to_supported_krx_markets() {
        assertThat(KrMarket.fromStockMarket(" kospi ")).contains(KrMarket.KOSPI);
        assertThat(KrMarket.fromStockMarket("KoSdAq")).contains(KrMarket.KOSDAQ);
    }

    @Test
    void null_and_unsupported_stock_markets_are_not_mapped() {
        assertThat(KrMarket.fromStockMarket(null)).isEmpty();
        assertThat(KrMarket.fromStockMarket("KR_ETC")).isEmpty();
        assertThat(KrMarket.fromStockMarket("NASDAQ")).isEmpty();
    }

    @Test
    void circuit_breaker_requires_stage_and_no_sidecar_direction() {
        assertThatThrownBy(() -> MarketEvent.circuitBreaker(
                MarketEventSource.KRX_KIND,
                "20260713000658",
                KrMarket.KOSPI,
                0,
                TRIGGERED_AT,
                HALT_UNTIL,
                PUBLISHED_AT,
                RECEIVED_AT,
                "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                SOURCE_URL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sidecar_requires_direction_and_keeps_circuit_breaker_stage_null() {
        MarketEvent event = MarketEvent.sidecar(
                MarketEventSource.KRX_KIND, "20260713000659", KrMarket.KOSDAQ,
                SidecarDirection.SELL, TRIGGERED_AT, HALT_UNTIL, PUBLISHED_AT, RECEIVED_AT,
                "코스닥시장 프로그램매매 호가 일시효력정지", SOURCE_URL);

        assertThat(event.getEventType()).isEqualTo(MarketEventType.SIDECAR);
        assertThat(event.getSidecarDirection()).isEqualTo(SidecarDirection.SELL);
        assertThat(event.getCircuitBreakerStage()).isNull();
    }

    @Test
    void sidecar_rejects_missing_direction() {
        assertThatThrownBy(() -> MarketEvent.sidecar(
                MarketEventSource.KRX_KIND, "20260713000660", KrMarket.KOSPI,
                null, TRIGGERED_AT, HALT_UNTIL, PUBLISHED_AT, RECEIVED_AT,
                "사이드카", SOURCE_URL))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void factory_validates_source_id_url_title_and_time_order() {
        assertThatThrownBy(() -> validEvent("123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validEventWithUrl(URI.create("http://kind.krx.co.kr/event")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validEventWithTitle(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validEventWithTitle("x".repeat(301)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketEvent.circuitBreaker(
                MarketEventSource.KRX_KIND, "20260713000661", KrMarket.KOSPI, 1,
                HALT_UNTIL, TRIGGERED_AT, PUBLISHED_AT, RECEIVED_AT,
                "서킷브레이커", SOURCE_URL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MarketEvent validEvent(String sourceEventId) {
        return MarketEvent.circuitBreaker(MarketEventSource.KRX_KIND, sourceEventId, KrMarket.KOSPI,
                1, TRIGGERED_AT, HALT_UNTIL, PUBLISHED_AT, RECEIVED_AT, "서킷브레이커", SOURCE_URL);
    }

    private MarketEvent validEventWithUrl(URI sourceUrl) {
        return MarketEvent.circuitBreaker(MarketEventSource.KRX_KIND, "20260713000662", KrMarket.KOSPI,
                1, TRIGGERED_AT, HALT_UNTIL, PUBLISHED_AT, RECEIVED_AT, "서킷브레이커", sourceUrl);
    }

    private MarketEvent validEventWithTitle(String title) {
        return MarketEvent.circuitBreaker(MarketEventSource.KRX_KIND, "20260713000663", KrMarket.KOSPI,
                1, TRIGGERED_AT, HALT_UNTIL, PUBLISHED_AT, RECEIVED_AT, title, SOURCE_URL);
    }
}
