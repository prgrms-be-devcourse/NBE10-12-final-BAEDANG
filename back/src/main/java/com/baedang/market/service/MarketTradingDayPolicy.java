package com.baedang.market.service;

import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** 거래소 현지 거래일을 사용하며 정규장 경계는 공통 캘린더 포트로 확인한다. */
@Component
public class MarketTradingDayPolicy {
    private final MarketCalendarPort calendars;

    public MarketTradingDayPolicy(MarketCalendarPort calendars) {
        this.calendars = calendars;
    }

    public MarketCalendarDay calendar(MarketCountry country, LocalDate date) {
        MarketCalendarDay day = country == MarketCountry.KR
                ? calendars.fetchKrMarketCalendar(date) : calendars.fetchUsMarketCalendar(date);
        return MarketCalendarDay.requireMatching(day, country, date);
    }

    /** 정확히 regularCloseAt에 관측된 시세는 허용하지만 해당 시각에 시장이 열려 있다는 뜻은 아니다. */
    public Optional<LocalDate> quoteTradeDate(MarketCountry country, Instant at) {
        LocalDate date = at.atZone(country.zoneId()).toLocalDate();
        MarketCalendarDay day = calendar(country, date);
        return day.acceptsRegularQuoteAt(at)
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
