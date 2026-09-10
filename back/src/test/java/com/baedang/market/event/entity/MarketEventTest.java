package com.baedang.market.event.entity;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketEventTest {

    private static final Instant TRIGGERED_AT = Instant.parse("2026-07-13T04:28:32Z");
    private static final Instant HALT_UNTIL = Instant.parse("2026-07-13T04:48:32Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-13T04:29:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-13T04:29:07Z");
    private static final URI SOURCE_URL = URI.create(
            "https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm");

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
}
