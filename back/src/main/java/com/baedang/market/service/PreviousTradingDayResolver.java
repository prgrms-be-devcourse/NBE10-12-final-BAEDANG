package com.baedang.market.service;

import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.standard.utils.Pacer;
import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/** 시장 캘린더를 역조회해 현재 장의 직전 거래일을 찾습니다. */
@Component
public class PreviousTradingDayResolver {

    private static final Logger log = LoggerFactory.getLogger(PreviousTradingDayResolver.class);
    private static final int MAX_LOOKBACK_DAYS = 14;
    private static final int MARKET_INFO_TPS = 3;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final MarketCalendarPort marketCalendarPort;
    private final Clock clock;

    public PreviousTradingDayResolver(MarketCalendarPort marketCalendarPort, Clock clock) {
        this.marketCalendarPort = marketCalendarPort;
        this.clock = clock;
    }

    public Optional<LocalDate> resolve(MarketCountry marketCountry) {
        LocalDate today = clock.instant()
                .atZone(marketCountry == MarketCountry.US ? NY : KST)
                .toLocalDate();
        Pacer pacer = Pacer.forTps(MARKET_INFO_TPS);
        boolean requestedCalendar = false;

        for (int daysAgo = 1; daysAgo <= MAX_LOOKBACK_DAYS; daysAgo++) {
            LocalDate candidate = today.minusDays(daysAgo);
            if (isWeekend(candidate)) continue;

            if (requestedCalendar) pacer.pace();
            requestedCalendar = true;

            MarketCalendarDay calendarDay;
            try {
                calendarDay = fetch(marketCountry, candidate);
            } catch (Exception exception) {
                log.warn(
                        "[prev-close] 직전 거래일 조회 실패: market={} candidate={} reason={}",
                        marketCountry,
                        candidate,
                        exception.getMessage()
                );
                return Optional.empty();
            }

            if (!isMatchingResponse(marketCountry, candidate, calendarDay)) {
                log.warn(
                        "[prev-close] 시장 캘린더 응답 불일치: market={} candidate={}",
                        marketCountry,
                        candidate
                );
                return Optional.empty();
            }
            if (calendarDay.isOpen()) return Optional.of(candidate);
        }

        log.warn(
                "[prev-close] 직전 거래일을 찾지 못함: market={} lookbackDays={}",
                marketCountry,
                MAX_LOOKBACK_DAYS
        );
        return Optional.empty();
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
