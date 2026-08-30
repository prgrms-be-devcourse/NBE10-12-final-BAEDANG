package com.baedang.market.service;

import com.baedang.market.dto.MarketStatusResponse;
import com.baedang.market.dto.MarketStatusResponse.Market;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketStatusServiceTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Mock
    MarketCalendarPort port;

    @BeforeEach
    void stubCalendar() {
        // 평일=거래일(KR 09:00~15:30, US 22:30~익일 05:00 KST), 주말=휴장. 서비스가 날짜별로 조회하는 걸 그대로 흉내.
        when(port.fetchKrMarketCalendar(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return isWeekday(d) ? krOpenDay(d) : closedDay(MarketCountry.KR, d);
        });
        when(port.fetchUsMarketCalendar(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return isWeekday(d) ? usOpenDay(d) : closedDay(MarketCountry.US, d);
        });
    }

    @Test
    @DisplayName("KR 개장 중 — open=true, opensAt/closesAt=오늘값, nextOpensAt=null")
    void krDuringSession() {
        Market kr = kr(at(2026, 8, 11, 12, 0)); // 화 12:00 KST

        assertThat(kr.open()).isTrue();
        assertThat(kr.opensAt()).isEqualTo(kstTime(2026, 8, 11, 9, 0));
        assertThat(kr.closesAt()).isEqualTo(kstTime(2026, 8, 11, 15, 30));
        assertThat(kr.nextOpensAt()).isNull();
    }

    @Test
    @DisplayName("KR 개장 전 — open=false, nextOpensAt=오늘 09:00 (Port의 nextOpensAt 함정 회피)")
    void krBeforeOpen() {
        Market kr = kr(at(2026, 8, 11, 8, 0)); // 화 08:00 KST

        assertThat(kr.open()).isFalse();
        assertThat(kr.opensAt()).isNull();
        assertThat(kr.closesAt()).isNull();
        assertThat(kr.nextOpensAt()).isEqualTo(kstTime(2026, 8, 11, 9, 0));
    }

    @Test
    @DisplayName("KR 마감 후 — nextOpensAt=다음 거래일(내일) 09:00")
    void krAfterClose() {
        Market kr = kr(at(2026, 8, 11, 16, 0)); // 화 16:00 KST

        assertThat(kr.open()).isFalse();
        assertThat(kr.nextOpensAt()).isEqualTo(kstTime(2026, 8, 12, 9, 0));
    }

    @Test
    @DisplayName("KR 휴장(토요일) — nextOpensAt=주말 건너뛴 월요일 09:00")
    void krHolidayWeekend() {
        Market kr = kr(at(2026, 8, 15, 10, 0)); // 토 10:00 KST

        assertThat(kr.open()).isFalse();
        assertThat(kr.nextOpensAt()).isEqualTo(kstTime(2026, 8, 17, 9, 0));
    }

    @Test
    @DisplayName("US 자정 교차 — 자정 이후에도 전날 세션이 열려 있으면 open=true (전날 lookback)")
    void usOpenAfterMidnight() {
        Market us = us(at(2026, 8, 12, 2, 0)); // 수 02:00 KST — 전날(화) 22:30~오늘 05:00 세션 중

        assertThat(us.open()).isTrue();
        assertThat(us.opensAt()).isEqualTo(kstTime(2026, 8, 11, 22, 30));
        assertThat(us.closesAt()).isEqualTo(kstTime(2026, 8, 12, 5, 0));
        assertThat(us.nextOpensAt()).isNull();
    }

    @Test
    @DisplayName("US 갭(05:00 마감 후 ~ 22:30 개장 전) — nextOpensAt=오늘 22:30 (자정 회귀 버그 방지)")
    void usGapBetweenCloseAndNextOpen() {
        // now.date+1 부터 스캔하면 오늘 22:30 개장을 건너뛰는 옛 설계 버그를 잡는 회귀 테스트.
        Market us = us(at(2026, 8, 12, 8, 0)); // 수 08:00 KST

        assertThat(us.open()).isFalse();
        assertThat(us.nextOpensAt()).isEqualTo(kstTime(2026, 8, 12, 22, 30));
    }

    @Test
    @DisplayName("US 개장 전 저녁 — nextOpensAt=오늘 22:30 (api-spec 예시)")
    void usBeforeEveningOpen() {
        Market us = us(at(2026, 8, 11, 20, 0)); // 화 20:00 KST

        assertThat(us.open()).isFalse();
        assertThat(us.nextOpensAt()).isEqualTo(kstTime(2026, 8, 11, 22, 30));
    }

    @Test
    @DisplayName("응답 형태 — markets 2개(KR·US), serverTime은 KST(+09:00)")
    void responseShape() {
        Instant now = at(2026, 8, 11, 12, 0);
        MarketStatusResponse response = service(now).getStatus();

        assertThat(response.markets()).hasSize(2);
        assertThat(response.markets()).extracting(Market::marketCountry)
                .containsExactly(MarketCountry.KR, MarketCountry.US);
        assertThat(response.serverTime().getOffset()).isEqualTo(KST);
        assertThat(response.serverTime().toInstant()).isEqualTo(now);
    }

    @Test
    @DisplayName("캐시 — 같은 (country,date)는 여러 번 호출해도 Port fetch 1회")
    void cacheFetchesOncePerDate() {
        MarketStatusService service = service(at(2026, 8, 11, 12, 0));

        service.getStatus();
        service.getStatus();

        // 개장 중이면 KR은 오늘 하루만 조회 → 두 번 호출해도 오늘 date는 딱 1회 fetch.
        verify(port, times(1)).fetchKrMarketCalendar(LocalDate.of(2026, 8, 11));
    }

    // --- helpers ---

    private Market kr(Instant now) {
        return market(now, MarketCountry.KR);
    }

    private Market us(Instant now) {
        return market(now, MarketCountry.US);
    }

    private Market market(Instant now, MarketCountry country) {
        return service(now).getStatus().markets().stream()
                .filter(m -> m.marketCountry() == country)
                .findFirst()
                .orElseThrow();
    }

    private MarketStatusService service(Instant now) {
        return new MarketStatusService(port, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Instant at(int y, int mo, int d, int h, int mi) {
        return kstTime(y, mo, d, h, mi).toInstant();
    }

    private static OffsetDateTime kstTime(int y, int mo, int d, int h, int mi) {
        return OffsetDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h, mi), KST);
    }

    private static boolean isWeekday(LocalDate d) {
        DayOfWeek dw = d.getDayOfWeek();
        return dw != DayOfWeek.SATURDAY && dw != DayOfWeek.SUNDAY;
    }

    private static MarketCalendarDay krOpenDay(LocalDate d) {
        return new MarketCalendarDay(MarketCountry.KR, d, true,
                kstTime(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 9, 0),
                kstTime(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 15, 30),
                null);
    }

    private static MarketCalendarDay usOpenDay(LocalDate d) {
        LocalDate next = d.plusDays(1);
        return new MarketCalendarDay(MarketCountry.US, d, true,
                kstTime(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 22, 30),
                kstTime(next.getYear(), next.getMonthValue(), next.getDayOfMonth(), 5, 0),
                null);
    }

    private static MarketCalendarDay closedDay(MarketCountry country, LocalDate d) {
        return new MarketCalendarDay(country, d, false, null, null, null);
    }
}
