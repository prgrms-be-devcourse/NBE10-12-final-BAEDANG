package com.baedang.market.service;

import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreviousTradingDayResolverTest {

    private final MarketCalendarPort marketCalendarPort = mock(MarketCalendarPort.class);

    @Test
    void 월요일_국내장의_직전_거래일은_금요일이다() {
        LocalDate friday = LocalDate.of(2026, 8, 28);
        when(marketCalendarPort.fetchKrMarketCalendar(friday))
                .thenReturn(calendar(MarketCountry.KR, friday, true));
        PreviousTradingDayResolver resolver = resolverAt("2026-08-31T00:00:00Z");

        Optional<LocalDate> result = resolver.resolve(MarketCountry.KR);

        assertThat(result).contains(friday);
        verify(marketCalendarPort).fetchKrMarketCalendar(friday);
        verify(marketCalendarPort, never()).fetchKrMarketCalendar(LocalDate.of(2026, 8, 30));
        verify(marketCalendarPort, never()).fetchKrMarketCalendar(LocalDate.of(2026, 8, 29));
    }

    @Test
    void 평일_휴장일이면_그보다_앞선_개장일까지_조회한다() {
        LocalDate holiday = LocalDate.of(2026, 8, 31);
        LocalDate previousFriday = LocalDate.of(2026, 8, 28);
        when(marketCalendarPort.fetchKrMarketCalendar(holiday))
                .thenReturn(calendar(MarketCountry.KR, holiday, false));
        when(marketCalendarPort.fetchKrMarketCalendar(previousFriday))
                .thenReturn(calendar(MarketCountry.KR, previousFriday, true));
        PreviousTradingDayResolver resolver = resolverAt("2026-09-01T00:00:00Z");

        Optional<LocalDate> result = resolver.resolve(MarketCountry.KR);

        assertThat(result).contains(previousFriday);
        verify(marketCalendarPort).fetchKrMarketCalendar(holiday);
        verify(marketCalendarPort).fetchKrMarketCalendar(previousFriday);
    }

    @Test
    void 미국장은_뉴욕_현지_날짜를_기준으로_조회한다() {
        LocalDate friday = LocalDate.of(2026, 8, 28);
        when(marketCalendarPort.fetchUsMarketCalendar(friday))
                .thenReturn(calendar(MarketCountry.US, friday, true));
        PreviousTradingDayResolver resolver = resolverAt("2026-08-31T14:00:00Z");

        assertThat(resolver.resolve(MarketCountry.US)).contains(friday);
        verify(marketCalendarPort).fetchUsMarketCalendar(friday);
    }

    @Test
    void 캘린더_조회가_실패하면_빈_결과를_반환한다() {
        LocalDate friday = LocalDate.of(2026, 8, 28);
        when(marketCalendarPort.fetchKrMarketCalendar(friday))
                .thenThrow(new IllegalStateException("calendar unavailable"));
        PreviousTradingDayResolver resolver = resolverAt("2026-08-31T00:00:00Z");

        assertThat(resolver.resolve(MarketCountry.KR)).isEmpty();
    }

    private PreviousTradingDayResolver resolverAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new PreviousTradingDayResolver(marketCalendarPort, clock);
    }

    private MarketCalendarDay calendar(
            MarketCountry marketCountry,
            LocalDate tradeDate,
            boolean open
    ) {
        return new MarketCalendarDay(marketCountry, tradeDate, open, null, null, null);
    }
}
