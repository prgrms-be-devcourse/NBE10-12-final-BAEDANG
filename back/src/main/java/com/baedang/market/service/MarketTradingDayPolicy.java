package com.baedang.market.service;

import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Exchange-local dates; session boundaries always come from the shared calendar port. */
@Component
public class MarketTradingDayPolicy {
    private final MarketCalendarPort calendars;

    public MarketTradingDayPolicy(MarketCalendarPort calendars) {
        this.calendars = calendars;
    }

    public MarketCalendarDay calendar(MarketCountry country, LocalDate date) {
        MarketCalendarDay day = country == MarketCountry.KR
                ? calendars.fetchKrMarketCalendar(date) : calendars.fetchUsMarketCalendar(date);
        if (day == null || day.marketCountry() != country || !date.equals(day.tradeDate())) {
            throw new IllegalStateException("Market calendar date mismatch");
        }
        return day;
    }

    /** Closing prints at exactly regularCloseAt are valid observations, but not an open session. */
    public Optional<LocalDate> quoteTradeDate(MarketCountry country, Instant at) {
        LocalDate date = at.atZone(country.zoneId()).toLocalDate();
        MarketCalendarDay day = calendar(country, date);
        return day.isOpen() && day.regularOpenAt() != null && day.regularCloseAt() != null
                && !at.isBefore(day.regularOpenAt().toInstant())
                && !at.isAfter(day.regularCloseAt().toInstant())
                ? Optional.of(date) : Optional.empty();
    }

    public Optional<LocalDate> previousTradingDay(MarketCountry country, LocalDate date) {
        for (int offset = 1; offset <= 14; offset++) {
            LocalDate candidate = date.minusDays(offset);
            if (candidate.getDayOfWeek().getValue() >= 6) continue;
            if (calendar(country, candidate).isOpen()) return Optional.of(candidate);
        }
        return Optional.empty();
    }
}
