package com.baedang.market.service;

import com.baedang.market.client.fake.FakeMarketCalendarPort;
import com.baedang.market.dto.MarketStatusResponse.Market;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarketStatusService} 를 <b>실제 {@link FakeMarketCalendarPort}</b> 와 결합해 검증한다
 * (DB·Spring 컨텍스트 불필요, conventions "integration: user-case based").
 *
 * <p>{@link MarketStatusServiceTest} 는 Port 를 stub 하므로 서비스의 <i>산수</i>만 증명한다.
 * 이 테스트는 서비스의 정방향 스캔·전날 lookback 이 <b>실제 Port 구현</b>(날짜 인자를 존중하고
 * US 세션이 자정을 넘기는)과 맞물려 동작하는지 seam 을 넘어 확인한다.
 */
class MarketStatusServiceFakeCalendarTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final MarketStatusService service = new MarketStatusService(
            new FakeMarketCalendarPort(), fixedAt(2026, 8, 12, 8, 0));

    private MarketStatusService serviceAt(int y, int mo, int d, int h, int mi) {
        return new MarketStatusService(new FakeMarketCalendarPort(), fixedAt(y, mo, d, h, mi));
    }

    @Test
    @DisplayName("US 갭(수 08:00) — 실제 Fake 로도 nextOpensAt=오늘 22:30 (자정 회귀 방지)")
    void usGapWithRealFake() {
        Market us = market(service, MarketCountry.US);

        assertThat(us.open()).isFalse();
        assertThat(us.nextOpensAt()).isEqualTo(kst(2026, 8, 12, 22, 30));
    }

    @Test
    @DisplayName("US 자정 교차(수 02:00) — 실제 Fake 로도 전날 세션 open=true")
    void usMidnightWithRealFake() {
        Market us = market(serviceAt(2026, 8, 12, 2, 0), MarketCountry.US);

        assertThat(us.open()).isTrue();
        assertThat(us.opensAt()).isEqualTo(kst(2026, 8, 11, 22, 30));
        assertThat(us.closesAt()).isEqualTo(kst(2026, 8, 12, 5, 0));
    }

    @Test
    @DisplayName("KR 주말(토 10:00) — 실제 Fake 로도 정방향 스캔이 월요일 09:00 도달")
    void krWeekendScanWithRealFake() {
        Market kr = market(serviceAt(2026, 8, 15, 10, 0), MarketCountry.KR);

        assertThat(kr.open()).isFalse();
        assertThat(kr.nextOpensAt()).isEqualTo(kst(2026, 8, 17, 9, 0));
    }

    private static Market market(MarketStatusService service, MarketCountry country) {
        return service.getStatus().markets().stream()
                .filter(m -> m.marketCountry() == country)
                .findFirst()
                .orElseThrow();
    }

    private static Clock fixedAt(int y, int mo, int d, int h, int mi) {
        return Clock.fixed(kst(y, mo, d, h, mi).toInstant(), ZoneOffset.UTC);
    }

    private static OffsetDateTime kst(int y, int mo, int d, int h, int mi) {
        return OffsetDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h, mi), KST);
    }
}
