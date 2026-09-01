package com.baedang.market.provider;

import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MarketSessionProviderBridge}의 (country, date)별 캐싱 동작 검증.
 *
 * <p>이 캐시가 없으면 {@code currentSession()} 호출마다 {@link MarketCalendarPort}를
 * 그대로 다시 부르는데, 실제로 랭킹 화면에서 이게 반복 호출되어 Toss 요청 한도(429)를
 * 겪은 적이 있다 — 그래서 "같은 (시장, 날짜)는 한 번만 조회한다"는 게 이 클래스의
 * 핵심 계약이다.
 */
@ExtendWith(MockitoExtension.class)
class MarketSessionProviderBridgeTest {

    @Mock
    MarketCalendarPort marketCalendarPort;

    MarketSessionProviderBridge bridge;

    private static final LocalDate DATE = LocalDate.of(2026, 8, 31);
    // KST 09:00 ~ 15:30, UTC로는 00:00 ~ 06:30.
    private static final OffsetDateTime KR_OPEN = OffsetDateTime.of(2026, 8, 31, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime KR_CLOSE = OffsetDateTime.of(2026, 8, 31, 6, 30, 0, 0, ZoneOffset.UTC);
    // 정규장 도중 한 시점(KST 10시 = UTC 01시).
    private static final Instant DURING_KR_SESSION = Instant.parse("2026-08-31T01:00:00Z");

    @BeforeEach
    void setUp() {
        bridge = new MarketSessionProviderBridge(marketCalendarPort);
    }

    @Test
    void 같은_날짜를_여러_번_조회해도_KR_캘린더는_한_번만_호출한다() {
        when(marketCalendarPort.fetchKrMarketCalendar(DATE))
                .thenReturn(openDay(MarketCountry.KR, KR_OPEN, KR_CLOSE));

        MarketSessionStatus first = bridge.currentSession(MarketCountry.KR, DURING_KR_SESSION);
        MarketSessionStatus second = bridge.currentSession(MarketCountry.KR, DURING_KR_SESSION);
        MarketSessionStatus third = bridge.currentSession(MarketCountry.KR, DURING_KR_SESSION.plusSeconds(30));

        assertThat(first.open()).isTrue();
        assertThat(second.open()).isTrue();
        assertThat(third.open()).isTrue();
        verify(marketCalendarPort, times(1)).fetchKrMarketCalendar(DATE);
    }

    @Test
    void 날짜가_바뀌면_KR_캘린더를_다시_조회한다() {
        LocalDate nextDate = DATE.plusDays(1);
        when(marketCalendarPort.fetchKrMarketCalendar(DATE))
                .thenReturn(openDay(MarketCountry.KR, KR_OPEN, KR_CLOSE));
        when(marketCalendarPort.fetchKrMarketCalendar(nextDate))
                .thenReturn(closedDay(MarketCountry.KR, null));

        bridge.currentSession(MarketCountry.KR, DURING_KR_SESSION);
        bridge.currentSession(MarketCountry.KR, DURING_KR_SESSION.plus(java.time.Duration.ofDays(1)));

        verify(marketCalendarPort, times(1)).fetchKrMarketCalendar(DATE);
        verify(marketCalendarPort, times(1)).fetchKrMarketCalendar(nextDate);
    }

    @Test
    void KR과_US는_서로_다른_캐시_키라_한쪽_캐싱이_다른쪽_호출에_영향을_주지_않는다() {
        when(marketCalendarPort.fetchKrMarketCalendar(DATE))
                .thenReturn(openDay(MarketCountry.KR, KR_OPEN, KR_CLOSE));
        when(marketCalendarPort.fetchUsMarketCalendar(DATE))
                .thenReturn(closedDay(MarketCountry.US, null));
        when(marketCalendarPort.fetchUsMarketCalendar(DATE.minusDays(1)))
                .thenReturn(closedDay(MarketCountry.US, null));

        bridge.currentSession(MarketCountry.KR, DURING_KR_SESSION);
        bridge.currentSession(MarketCountry.US, DURING_KR_SESSION);
        bridge.currentSession(MarketCountry.KR, DURING_KR_SESSION);
        bridge.currentSession(MarketCountry.US, DURING_KR_SESSION);

        verify(marketCalendarPort, times(1)).fetchKrMarketCalendar(DATE);
        verify(marketCalendarPort, times(1)).fetchUsMarketCalendar(DATE);
        // US는 오늘이 닫혀 있으면 전날도 확인하는 기존 로직 그대로 — 그 전날 조회도 캐싱된다.
        verify(marketCalendarPort, times(1)).fetchUsMarketCalendar(DATE.minusDays(1));
    }

    @Test
    void US_전날_확인도_캐싱된다() {
        when(marketCalendarPort.fetchUsMarketCalendar(DATE)).thenReturn(closedDay(MarketCountry.US, null));
        when(marketCalendarPort.fetchUsMarketCalendar(DATE.minusDays(1))).thenReturn(closedDay(MarketCountry.US, null));

        bridge.currentSession(MarketCountry.US, DURING_KR_SESSION);
        bridge.currentSession(MarketCountry.US, DURING_KR_SESSION);

        verify(marketCalendarPort, times(1)).fetchUsMarketCalendar(DATE);
        verify(marketCalendarPort, times(1)).fetchUsMarketCalendar(DATE.minusDays(1));
    }

    private static MarketCalendarDay openDay(MarketCountry country, OffsetDateTime openAt, OffsetDateTime closeAt) {
        return new MarketCalendarDay(country, DATE, true, openAt, closeAt, null);
    }

    private static MarketCalendarDay closedDay(MarketCountry country, OffsetDateTime nextOpensAt) {
        return new MarketCalendarDay(country, DATE, false, null, null, nextOpensAt);
    }
}
