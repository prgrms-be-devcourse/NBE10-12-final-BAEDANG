package com.baedang.market.service;

import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LatestCompletedTradingDayResolverTest {

    private final MarketCalendarPort marketCalendarPort = mock(MarketCalendarPort.class);

    @Test
    void 월요일_장중_최신_확정_거래일은_금요일이다() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        LocalDate friday = LocalDate.of(2026, 8, 28);
        when(marketCalendarPort.fetchKrMarketCalendar(monday))
                .thenReturn(openCalendar(MarketCountry.KR, monday, "2026-08-31T15:30:00+09:00"));
        when(marketCalendarPort.fetchKrMarketCalendar(friday))
                .thenReturn(openCalendar(MarketCountry.KR, friday, "2026-08-28T15:30:00+09:00"));
        LatestCompletedTradingDayResolver resolver = resolverAt("2026-08-31T00:00:00Z");

        Optional<LocalDate> result = resolver.resolve(MarketCountry.KR);

        assertThat(result).contains(friday);
        verify(marketCalendarPort, never()).fetchKrMarketCalendar(LocalDate.of(2026, 8, 30));
        verify(marketCalendarPort, never()).fetchKrMarketCalendar(LocalDate.of(2026, 8, 29));
    }

    @Test
    void 국내장_마감_10분_후에는_오늘이_최신_확정_거래일이다() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(marketCalendarPort.fetchKrMarketCalendar(monday))
                .thenReturn(openCalendar(MarketCountry.KR, monday, "2026-08-31T15:30:00+09:00"));
        LatestCompletedTradingDayResolver resolver = resolverAt("2026-08-31T06:40:00Z");

        assertThat(resolver.resolve(MarketCountry.KR)).contains(monday);
        verify(marketCalendarPort, never()).fetchKrMarketCalendar(monday.minusDays(1));
    }

    @Test
    void 국내장_마감_10분_전에는_직전_거래일을_반환한다() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        LocalDate friday = LocalDate.of(2026, 8, 28);
        when(marketCalendarPort.fetchKrMarketCalendar(monday))
                .thenReturn(openCalendar(MarketCountry.KR, monday, "2026-08-31T15:30:00+09:00"));
        when(marketCalendarPort.fetchKrMarketCalendar(friday))
                .thenReturn(openCalendar(MarketCountry.KR, friday, "2026-08-28T15:30:00+09:00"));
        LatestCompletedTradingDayResolver resolver = resolverAt("2026-08-31T06:39:59Z");

        assertThat(resolver.resolve(MarketCountry.KR)).contains(friday);
    }

    @Test
    void 평일_연속_휴장일이면_그보다_앞선_개장일까지_조회한다() {
        LocalDate tuesday = LocalDate.of(2026, 9, 1);
        LocalDate monday = LocalDate.of(2026, 8, 31);
        LocalDate friday = LocalDate.of(2026, 8, 28);
        when(marketCalendarPort.fetchKrMarketCalendar(tuesday))
                .thenReturn(closedCalendar(MarketCountry.KR, tuesday));
        when(marketCalendarPort.fetchKrMarketCalendar(monday))
                .thenReturn(closedCalendar(MarketCountry.KR, monday));
        when(marketCalendarPort.fetchKrMarketCalendar(friday))
                .thenReturn(openCalendar(MarketCountry.KR, friday, "2026-08-28T15:30:00+09:00"));
        LatestCompletedTradingDayResolver resolver = resolverAt("2026-09-01T00:00:00Z");

        assertThat(resolver.resolve(MarketCountry.KR)).contains(friday);
    }

    @Test
    void 미국장도_캘린더의_DST_마감시각_10분_후에는_오늘을_반환한다() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(marketCalendarPort.fetchUsMarketCalendar(monday))
                .thenReturn(openCalendar(MarketCountry.US, monday, "2026-08-31T16:00:00-04:00"));
        LatestCompletedTradingDayResolver resolver = resolverAt("2026-08-31T20:10:00Z");

        assertThat(resolver.resolve(MarketCountry.US)).contains(monday);
    }

    @Test
    void 캘린더_조회가_실패하면_빈_결과를_반환한다() {
        LocalDate monday = LocalDate.of(2026, 8, 31);
        when(marketCalendarPort.fetchKrMarketCalendar(monday))
                .thenThrow(new IllegalStateException("calendar unavailable"));
        LatestCompletedTradingDayResolver resolver = resolverAt("2026-08-31T00:00:00Z");

        assertThat(resolver.resolve(MarketCountry.KR)).isEmpty();
    }

    private LatestCompletedTradingDayResolver resolverAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new LatestCompletedTradingDayResolver(marketCalendarPort, clock);
    }

    private MarketCalendarDay openCalendar(
            MarketCountry marketCountry,
            LocalDate tradeDate,
            String regularCloseAt
    ) {
        return new MarketCalendarDay(
                marketCountry,
                tradeDate,
                true,
                null,
                OffsetDateTime.parse(regularCloseAt),
                null);
    }

    private MarketCalendarDay closedCalendar(MarketCountry marketCountry, LocalDate tradeDate) {
        return new MarketCalendarDay(marketCountry, tradeDate, false, null, null, null);
    }
}
