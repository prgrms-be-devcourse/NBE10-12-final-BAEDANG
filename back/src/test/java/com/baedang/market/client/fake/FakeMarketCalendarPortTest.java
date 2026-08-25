package com.baedang.market.client.fake;

import com.baedang.market.port.MarketCalendarDay;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FakeMarketCalendarPort} 는 Spring 컨텍스트 없이도 다른 팀원이 바로 가져다
 * 쓸 수 있어야 한다 — 그 계약을 확인하는 테스트.
 */
class FakeMarketCalendarPortTest {

    private final FakeMarketCalendarPort port = new FakeMarketCalendarPort();

    @Test
    void 평일에는_장이_열린_것으로_반환한다() {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        MarketCalendarDay day = port.fetchKrMarketCalendar(monday);

        assertThat(day.isOpen()).isTrue();
        assertThat(day.regularOpenAt()).isNotNull();
        assertThat(day.regularCloseAt()).isNotNull();
    }

    @Test
    void 주말에는_장이_닫힌_것으로_반환한다() {
        LocalDate saturday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY));

        MarketCalendarDay day = port.fetchKrMarketCalendar(saturday);

        assertThat(day.isOpen()).isFalse();
        assertThat(day.regularOpenAt()).isNull();
    }

    @Test
    void 환율은_Toss_호출_없이_고정값을_반환한다() {
        assertThat(port.fetchExchangeRate().baseCurrency()).isEqualTo("USD");
        assertThat(port.fetchExchangeRate().quoteCurrency()).isEqualTo("KRW");
    }
}
