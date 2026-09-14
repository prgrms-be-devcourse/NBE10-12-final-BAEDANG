package com.baedang.e2e;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 시나리오 시각은 명시적으로만 전진합니다. */
public final class E2eClock extends Clock {
    private final AtomicReference<Instant> current = new AtomicReference<>(Instant.parse("2026-09-14T01:00:00Z"));
    public void set(Instant instant) { current.set(instant); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    @Override public Instant instant() { return current.get(); }
}
