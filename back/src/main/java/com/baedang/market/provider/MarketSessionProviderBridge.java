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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 날짜(country, date)별로 최대 한 번만 Toss 장 캘린더를 부르도록 캐싱한다.
     *
     * <p><b>캐싱 전에는 {@link #currentSession} 호출 1번마다 실제 Toss API를 그대로
     * 호출했다.</b> 그런데 {@code QuoteRealtimePolicy.isRealtime()} 하나가 이 메서드를
     * 최대 3번 부르고, 랭킹 화면은 목록에 있는 종목 수만큼 그걸 또 반복 호출한다 —
     * 결국 랭킹 페이지 하나를 새로고침할 때마다 같은 날짜의 캘린더를 수십 번씩
     * 중복 조회하는 구조였다. 실제로 이 문제로 Toss 요청 한도(429)를 겪은 뒤 캐시를
     * 추가했다.
     *
     * <p>장 캘린더는 하루 안에서는 바뀌지 않는 값(휴장일 여부·정규장 시각)이라 TTL이
     * 필요 없다 — {@code infra/schema.sql}도 이 데이터를 "하루 1회 적재"로 문서화하고
     * 있다. 날짜가 바뀌면 새 키로 다시 채워질 뿐이고, 하루에 최대 2건(KR/US)씩만
     * 늘어나는 규모라 별도 정리(eviction)는 두지 않았다.
     */
    private final Map<CacheKey, MarketCalendarDay> calendarCache = new ConcurrentHashMap<>();

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
        // computeIfAbsent는 같은 키에 대해 동시에 여러 스레드가 들어와도 실제 계산(=Toss
        // 호출)은 한 번만 실행되도록 보장한다 — 별도 락 없이 "동시 요청 몰림"까지 막힌다.
        return calendarCache.computeIfAbsent(
                new CacheKey(MarketCountry.KR, date), key -> marketCalendarPort.fetchKrMarketCalendar(key.date()));
    }

    private MarketCalendarDay usCalendar(LocalDate date) {
        return calendarCache.computeIfAbsent(
                new CacheKey(MarketCountry.US, date), key -> marketCalendarPort.fetchUsMarketCalendar(key.date()));
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

    private record CacheKey(MarketCountry marketCountry, LocalDate date) {
    }
}
