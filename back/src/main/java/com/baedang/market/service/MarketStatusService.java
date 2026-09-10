package com.baedang.market.service;

import com.baedang.market.dto.MarketStatusResponse.Market;
import com.baedang.market.dto.MarketStatusResponse;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 거래소 현지 날짜로 현재 정규장과 다음 개장을 조회한다.
 * 캘린더 조회는 기존 Port 캐시를 공유하며 응답 시각은 KST로 표시한다.
 */
@Service
public class MarketStatusService {

    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

    /** 연휴가 아무리 길어도 이 안에 다음 거래일이 있다. 무한 루프·Port 폭주 안전장치. */
    private static final int MAX_SCAN_DAYS = 14;

    private final MarketCalendarPort marketCalendarPort;
    private final Clock clock;

    public MarketStatusService(MarketCalendarPort marketCalendarPort, Clock clock) {
        this.marketCalendarPort = marketCalendarPort;
        this.clock = clock;
    }

    public MarketStatusResponse getStatus() {
        Instant now = clock.instant();
        List<Market> markets = List.of(
                statusOf(MarketCountry.KR, now),
                statusOf(MarketCountry.US, now)
        );
        OffsetDateTime serverTime = OffsetDateTime.ofInstant(now, KST_OFFSET);
        return new MarketStatusResponse(markets, serverTime);
    }

    private Market statusOf(MarketCountry country, Instant now) {
        LocalDate today = now.atZone(country.zoneId()).toLocalDate();
        MarketCalendarDay active = activeOpenDay(country, today, now);
        if (active != null) {
            return new Market(country, true, active.regularOpenAt(), active.regularCloseAt(), null);
        }
        OffsetDateTime nextOpensAt = scanNextOpen(country, today, now);
        return new Market(country, false, null, null, nextOpensAt);
    }

    private MarketCalendarDay activeOpenDay(MarketCountry country, LocalDate today, Instant now) {
        MarketCalendarDay day = dayFor(country, today);
        return isNowWithin(day, now) ? day : null;
    }

    /**
     * {@code today} 부터 미래로 첫 개장 시각을 찾는다. {@code openAt > now} 라서 오늘 이미 지난
     * 세션이나 개장 후 시각은 자동으로 건너뛴다 → 개장 전/휴장/마감 후를 한 번에 처리.
     */
    private OffsetDateTime scanNextOpen(MarketCountry country, LocalDate today, Instant now) {
        for (int i = 0; i <= MAX_SCAN_DAYS; i++) {
            MarketCalendarDay day = dayFor(country, today.plusDays(i));
            if (day.isOpen()
                    && day.regularOpenAt() != null
                    && day.regularOpenAt().toInstant().isAfter(now)) {
                return day.regularOpenAt();
            }
        }
        return null;
    }

    private boolean isNowWithin(MarketCalendarDay day, Instant now) {
        if (!day.isOpen() || day.regularOpenAt() == null || day.regularCloseAt() == null) {
            return false;
        }
        Instant openAt = day.regularOpenAt().toInstant();
        Instant closeAt = day.regularCloseAt().toInstant();
        return !now.isBefore(openAt) && now.isBefore(closeAt);
    }

    private MarketCalendarDay dayFor(MarketCountry country, LocalDate date) {
        MarketCalendarDay day = country == MarketCountry.KR
                ? marketCalendarPort.fetchKrMarketCalendar(date) : marketCalendarPort.fetchUsMarketCalendar(date);
        if (day == null || day.marketCountry() != country || !date.equals(day.tradeDate())) {
            throw new IllegalStateException("Market calendar date mismatch");
        }
        return day;
    }
}
