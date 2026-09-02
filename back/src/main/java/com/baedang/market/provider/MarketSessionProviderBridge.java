package com.baedang.market.provider;

import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * {@link MarketSessionProvider}의 구현체.
 *
 * <p>거래 모듈이 필요로 하는 "지금 장이 열려 있는가 + 그 판단이 언제까지 유효한가"
 * ({@code MarketSessionProvider.currentSession(country, Instant)})와, 시장 데이터 모듈이
 * 제공하는 장 캘린더 원본({@code MarketCalendarPort.fetchKrMarketCalendar(LocalDate)})은
 * 모양이 다릅니다. 이 클래스가 그 변환을 담당합니다 — 거래 모듈은 저장 방식이나 캘린더
 * 응답 구조를 전혀 몰라도 됩니다.
 *
 * <p>세션 판단 로직 자체는 {@code MarketCalendarPort} 구현체(Toss 응답)가 돌려주는
 * {@code regularOpenAt}/{@code regularCloseAt}을 그대로 신뢰합니다 — DST 계산은
 * 여기서 하지 않습니다 (AGENTS.md 규칙).
 * 동일 시장·날짜의 중복 외부 조회는 주입되는 {@link CachingMarketCalendarPort}가
 * 다른 캘린더 사용처와 함께 제거합니다.
 */
@Component
public class MarketSessionProviderBridge implements MarketSessionProvider {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MarketCalendarPort marketCalendarPort;

    public MarketSessionProviderBridge(MarketCalendarPort marketCalendarPort) {
        this.marketCalendarPort = marketCalendarPort;
    }

    @Override
    public MarketSessionStatus currentSession(MarketCountry marketCountry, Instant now) {
        LocalDate today = now.atZone(KST).toLocalDate();

        if (marketCountry == MarketCountry.KR) {
            return statusOf(krCalendar(today), now);
        }

        // 미국 정규장은 KST 기준 자정을 넘기므로(예: 22:30~익일 05:00),
        // 오늘 날짜 조회만으로는 자정 이후 시간대를 놓칠 수 있어 전날 조회분도 함께 확인한다.
        MarketSessionStatus todayStatus = statusOf(usCalendar(today), now);
        if (todayStatus.open()) {
            return todayStatus;
        }
        return statusOf(usCalendar(today.minusDays(1)), now);
    }

    private MarketCalendarDay krCalendar(LocalDate date) {
        return marketCalendarPort.fetchKrMarketCalendar(date);
    }

    private MarketCalendarDay usCalendar(LocalDate date) {
        return marketCalendarPort.fetchUsMarketCalendar(date);
    }

    private MarketSessionStatus statusOf(MarketCalendarDay day, Instant now) {
        if (!day.isOpen() || day.regularOpenAt() == null || day.regularCloseAt() == null) {
            return MarketSessionStatus.closed();
        }
        Instant openAt = day.regularOpenAt().toInstant();
        Instant closeAt = day.regularCloseAt().toInstant();
        return !now.isBefore(openAt) && now.isBefore(closeAt)
                ? new MarketSessionStatus(true, closeAt)
                : MarketSessionStatus.closed();
    }
}
