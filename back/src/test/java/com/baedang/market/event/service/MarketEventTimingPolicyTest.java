package com.baedang.market.event.service;

import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.model.ConfirmedMarketEvent;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketEventTimingPolicyTest {

    private static final Instant TRIGGERED = Instant.parse("2026-07-13T04:28:32Z");

    private MarketCalendarPort calendar;
    private MarketEventTimingPolicy policy;

    @BeforeEach
    void setUp() {
        calendar = mock(MarketCalendarPort.class);
        policy = new MarketEventTimingPolicy(calendar);
    }

    @Test
    void stage_one_and_two_end_after_twenty_minutes() {
        assertThat(policy.haltUntil(confirmedCb(1, TRIGGERED)))
                .isEqualTo(TRIGGERED.plus(Duration.ofMinutes(20)));
        assertThat(policy.haltUntil(confirmedCb(2, TRIGGERED)))
                .isEqualTo(TRIGGERED.plus(Duration.ofMinutes(20)));
        verifyNoInteractions(calendar);
    }

    @Test
    void stage_three_uses_the_market_calendar_close() {
        LocalDate date = LocalDate.of(2026, 7, 13);
        when(calendar.fetchKrMarketCalendar(date)).thenReturn(new MarketCalendarDay(
                MarketCountry.KR, date, true,
                OffsetDateTime.parse("2026-07-13T09:00:00+09:00"),
                OffsetDateTime.parse("2026-07-13T15:30:00+09:00"), null));

        assertThat(policy.haltUntil(confirmedCb(3, TRIGGERED)))
                .isEqualTo(Instant.parse("2026-07-13T06:30:00Z"));
    }

    @Test
    void stage_three_rejects_closed_or_malformed_calendar() {
        LocalDate date = LocalDate.of(2026, 7, 13);
        when(calendar.fetchKrMarketCalendar(date)).thenReturn(new MarketCalendarDay(
                MarketCountry.KR, date, false, null, null, null));

        assertThatThrownBy(() -> policy.haltUntil(confirmedCb(3, TRIGGERED)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stage_three_rejects_close_before_or_at_trigger() {
        LocalDate date = LocalDate.of(2026, 7, 13);
        when(calendar.fetchKrMarketCalendar(date)).thenReturn(new MarketCalendarDay(
                MarketCountry.KR, date, true,
                OffsetDateTime.parse("2026-07-13T09:00:00+09:00"),
                OffsetDateTime.parse("2026-07-13T13:00:00+09:00"), null)); // 13:00 KST = 04:00Z < TRIGGERED (04:28:32Z)

        assertThatThrownBy(() -> policy.haltUntil(confirmedCb(3, TRIGGERED)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sidecar_ends_after_five_minutes_without_calendar_lookup() {
        ConfirmedMarketEvent sidecar = new ConfirmedMarketEvent(
                "20260715000125",
                KrMarket.KOSPI,
                MarketEventType.SIDECAR,
                null,
                SidecarDirection.BUY,
                TRIGGERED,
                TRIGGERED,
                TRIGGERED,
                "사이드카",
                URI.create("https://kind.krx.co.kr/external/2026/07/15/000066/20260715000125/99404.htm")
        );

        assertThat(policy.haltUntil(sidecar))
                .isEqualTo(TRIGGERED.plus(Duration.ofMinutes(5)));
        verifyNoInteractions(calendar);
    }

    private ConfirmedMarketEvent confirmedCb(int stage, Instant triggered) {
        return new ConfirmedMarketEvent(
                "20260713000658",
                KrMarket.KOSPI,
                MarketEventType.CIRCUIT_BREAKER,
                stage,
                null,
                triggered,
                triggered,
                triggered,
                "서킷브레이커",
                URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm")
        );
    }
}
