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
 * {@link MarketSessionProvider}의 임시 구현체.
 *
 * <p><b>⚠️ 이건 다훈님 PR(#7)이 정의한 인터페이스와, 제가 만든 {@link MarketCalendarPort}
 * 사이의 임시 다리(bridge)입니다.</b> 둘이 장 운영 여부를 서로 다른 모양으로 표현하고 있어서
 * ({@code MarketSessionProvider.currentSession(country, Instant)} vs
 * {@code MarketCalendarPort.fetchKrMarketCalendar(LocalDate)}), 팀에서 어느 쪽으로
 * 통일할지 정해질 때까지 백엔드가 최소한 기동은 되도록 얇은 변환만 합니다.
 * <b>Port 설계가 정리되면 이 클래스는 지우거나 교체하세요.</b>
 *
 * <p>세션 판단 로직 자체는 {@code MarketCalendarPort} 구현체(Toss 응답 또는
 * {@code FakeMarketCalendarPort})가 돌려주는 {@code regularOpenAt}/{@code regularCloseAt}을
 * 그대로 신뢰합니다 — DST 계산은 여기서 하지 않습니다 (AGENTS.md 규칙).
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
            return statusOf(marketCalendarPort.fetchKrMarketCalendar(today), now);
        }

        // 미국 정규장은 KST 기준 자정을 넘기므로(예: 22:30~익일 05:00),
        // 오늘 날짜 조회만으로는 자정 이후 시간대를 놓칠 수 있어 전날 조회분도 함께 확인한다.
        MarketSessionStatus todayStatus = statusOf(
                marketCalendarPort.fetchUsMarketCalendar(today), now);
        if (todayStatus.open()) {
            return todayStatus;
        }
        return statusOf(marketCalendarPort.fetchUsMarketCalendar(today.minusDays(1)), now);
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
