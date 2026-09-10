package com.baedang.market.service;

import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketTradingDayPolicyTest {
    private final MarketCalendarPort calendars = mock(MarketCalendarPort.class);
    private final MarketTradingDayPolicy policy = new MarketTradingDayPolicy(calendars);

    @ParameterizedTest
    @CsvSource({"2026-03-06,14:30:00", "2026-03-09,13:30:00", "2026-10-30,13:30:00", "2026-11-02,14:30:00"})
    void dstUsesNewYorkDateAndCalendarBoundaries(String dateText, String utcOpen) {
        LocalDate date = LocalDate.parse(dateText);
        var open = date.atTime(9,30).atZone(MarketCountry.US.zoneId()).toOffsetDateTime();
        var close = open.plusHours(6).plusMinutes(30);
        when(calendars.fetchUsMarketCalendar(date)).thenReturn(new MarketCalendarDay(MarketCountry.US,date,true,open,close,null));
        assertThat(open.toInstant()).isEqualTo(Instant.parse(dateText+"T"+utcOpen+"Z"));
        assertThat(policy.quoteTradeDate(MarketCountry.US,open.toInstant().minusSeconds(1))).isEmpty();
        assertThat(policy.quoteTradeDate(MarketCountry.US,open.toInstant())).contains(date);
        assertThat(policy.quoteTradeDate(MarketCountry.US,close.toInstant())).contains(date);
        assertThat(policy.quoteTradeDate(MarketCountry.US,close.toInstant().plusSeconds(1))).isEmpty();
    }

    @Test void earlyCloseAndHolidayUseCalendar() {
        LocalDate friday = LocalDate.of(2026,11,27);
        var open = friday.atTime(9,30).atZone(MarketCountry.US.zoneId()).toOffsetDateTime();
        when(calendars.fetchUsMarketCalendar(friday)).thenReturn(new MarketCalendarDay(MarketCountry.US,friday,true,open,open.plusHours(3).plusMinutes(30),null));
        assertThat(policy.quoteTradeDate(MarketCountry.US,open.plusHours(4).toInstant())).isEmpty();
        LocalDate holiday = friday.minusDays(1);
        when(calendars.fetchUsMarketCalendar(holiday)).thenReturn(new MarketCalendarDay(MarketCountry.US,holiday,false,null,null,null));
        LocalDate wednesday = holiday.minusDays(1);
        when(calendars.fetchUsMarketCalendar(wednesday)).thenReturn(new MarketCalendarDay(MarketCountry.US,wednesday,true,null,null,null));
        assertThat(policy.previousTradingDay(MarketCountry.US,friday)).contains(wednesday);
    }

    @Test void mismatchedCalendarCannotCertifyDate() {
        LocalDate date = LocalDate.of(2026,9,11);
        when(calendars.fetchUsMarketCalendar(date)).thenReturn(new MarketCalendarDay(MarketCountry.US,date.minusDays(1),true,null,null,null));
        assertThatThrownBy(() -> policy.calendar(MarketCountry.US,date)).isInstanceOf(IllegalStateException.class);
    }
}
