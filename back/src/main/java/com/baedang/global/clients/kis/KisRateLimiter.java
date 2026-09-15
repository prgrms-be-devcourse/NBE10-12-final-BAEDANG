package com.baedang.global.clients.kis;

import java.util.concurrent.TimeUnit;

import com.baedang.global.clients.FixedIntervalGate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class KisRateLimiter {

    private final FixedIntervalGate gate;
    private final Timer waitTimer;
    private final Counter acquiredCounter;

    public KisRateLimiter(FixedIntervalGate gate) {
        this(gate, new SimpleMeterRegistry());
    }

    public KisRateLimiter(FixedIntervalGate gate, MeterRegistry meterRegistry) {
        this.gate = gate;
        this.waitTimer = Timer.builder("kis.ratelimiter.wait")
                .description("KIS 조회 rate limiter 대기 시간")
                .tag("group", "QUERY")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.acquiredCounter = Counter.builder("kis.ratelimiter.acquired")
                .description("KIS 조회 permit 획득 수")
                .tag("group", "QUERY")
                .register(meterRegistry);
    }

    public void acquire() {
        long startedAt = System.nanoTime();
        gate.acquire();
        waitTimer.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        acquiredCounter.increment();
    }
}
