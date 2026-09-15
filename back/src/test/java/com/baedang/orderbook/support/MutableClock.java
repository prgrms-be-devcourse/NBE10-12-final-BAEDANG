package com.baedang.orderbook.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 테스트에서 수면을 쓰지 않고 시간을 전진시키기 위한 조정 가능한 Clock. */
public final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this(new AtomicReference<>(initial), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
        this.current = current;
        this.zone = zone;
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new MutableClock(current, zone); }
    @Override public Instant instant() { return current.get(); }

    public void advance(Duration duration) {
        current.updateAndGet(value -> value.plus(duration));
    }

    public void setCurrent(Instant instant) {
        current.set(instant);
    }
}
