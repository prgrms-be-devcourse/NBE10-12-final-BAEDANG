package com.baedang.e2e;

import com.baedang.stock.entity.MarketCountry;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 시나리오 시각은 명시적으로만 전진합니다. */
public final class E2eClock extends Clock {
    // 두 시장 모두 같은 시나리오 날짜의 거래소 현지 오전 10시에 시작합니다.
    private static final LocalDateTime SCENARIO_START = LocalDateTime.of(2026, 9, 14, 10, 0);
    private final AtomicReference<Instant> current = new AtomicReference<>(scenarioStart(MarketCountry.KR));
    private static Instant scenarioStart(MarketCountry country) {
        return SCENARIO_START.atZone(country.zoneId()).toInstant();
    }
    public void reset(MarketCountry country) { set(scenarioStart(country)); }
    public void set(Instant instant) { current.set(instant); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    @Override public Instant instant() { return current.get(); }
}
