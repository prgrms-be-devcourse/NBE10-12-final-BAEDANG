package com.baedang.market.service;

import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** 현재 시점에 캔들이 확정됐어야 하는 가장 최근 거래일을 시장 캘린더로 찾습니다. */
@Component
public class LatestCompletedTradingDayResolver {

    private static final Logger log = LoggerFactory.getLogger(LatestCompletedTradingDayResolver.class);
    private static final int MAX_LOOKBACK_DAYS = 14;
    private static final Duration FINALIZATION_DELAY = Duration.ofMinutes(10);

    private final MarketCalendarPort marketCalendarPort;
    private final Clock clock;

    public LatestCompletedTradingDayResolver(MarketCalendarPort marketCalendarPort, Clock clock) {
        this.marketCalendarPort = marketCalendarPort;
        this.clock = clock;
    }

    public Optional<LocalDate> resolve(MarketCountry marketCountry) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(marketCountry.zoneId()).toLocalDate();

        if (!isWeekend(today)) {
            Optional<MarketCalendarDay> todayCalendar = fetchValidated(marketCountry, today);
            if (todayCalendar.isEmpty()) return Optional.empty();
            if (isFinalized(todayCalendar.get(), now)) return Optional.of(today);
        }

        for (int daysAgo = 1; daysAgo <= MAX_LOOKBACK_DAYS; daysAgo++) {
            LocalDate candidate = today.minusDays(daysAgo);
            if (isWeekend(candidate)) continue;

            Optional<MarketCalendarDay> calendar = fetchValidated(marketCountry, candidate);
            if (calendar.isEmpty()) return Optional.empty();
            if (calendar.get().isOpen()) return Optional.of(candidate);
        }

        log.warn(
                "[trading-day] 최신 확정 거래일을 찾지 못함: market={} lookbackDays={}",
                marketCountry,
                MAX_LOOKBACK_DAYS
        );
        return Optional.empty();
    }

    private Optional<MarketCalendarDay> fetchValidated(
            MarketCountry marketCountry,
            LocalDate date
    ) {
        MarketCalendarDay calendarDay;
        try {
            calendarDay = fetch(marketCountry, date);
        } catch (Exception exception) {
            log.warn(
                    "[trading-day] 시장 캘린더 조회 실패: market={} candidate={} reason={}",
                    marketCountry,
                    date,
                    exception.getMessage()
            );
            return Optional.empty();
        }

        if (!isMatchingResponse(marketCountry, date, calendarDay)) {
            log.warn(
                    "[trading-day] 시장 캘린더 응답 불일치: market={} candidate={}",
                    marketCountry,
                    date
            );
            return Optional.empty();
        }
        return Optional.of(calendarDay);
    }

    private boolean isFinalized(MarketCalendarDay calendarDay, Instant now) {
        return calendarDay.isOpen()
                && calendarDay.regularCloseAt() != null
                && !now.isBefore(calendarDay.regularCloseAt().toInstant().plus(FINALIZATION_DELAY));
    }

    private MarketCalendarDay fetch(MarketCountry marketCountry, LocalDate date) {
        return switch (marketCountry) {
            case KR -> marketCalendarPort.fetchKrMarketCalendar(date);
            case US -> marketCalendarPort.fetchUsMarketCalendar(date);
        };
    }

    private boolean isMatchingResponse(
            MarketCountry marketCountry,
            LocalDate candidate,
            MarketCalendarDay calendarDay
    ) {
        return calendarDay != null
                && calendarDay.marketCountry() == marketCountry
                && candidate.equals(calendarDay.tradeDate());
    }

    private boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
