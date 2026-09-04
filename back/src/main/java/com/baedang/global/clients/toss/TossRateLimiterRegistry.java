package com.baedang.global.clients.toss;


import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Toss API 그룹별 gate를 보관하는 싱글톤.
 * 스케줄러와 사용자 요청이 같은 그룹이면 같은 gate를 공유해 호출량이 합산된다.
 *
 * <p>그룹별로 다음을 계측한다(이슈 #70 — 부하 중 토스 페이싱이 병목인지 관측):
 * <ul>
 *   <li>{@code toss_ratelimiter_wait_seconds} — {@link #acquire} 대기 시간(히스토그램)</li>
 *   <li>{@code toss_ratelimiter_acquired_total} — permit 획득 수</li>
 *   <li>{@code toss_ratelimiter_rejected_total} — {@link #tryAcquire} 거절 수</li>
 * </ul>
 */
@Component
public class TossRateLimiterRegistry {

    private final Map<TossApiGroup, FixedIntervalGate> gates;
    private final Map<TossApiGroup, Timer> waitTimers;
    private final Map<TossApiGroup, Counter> acquiredCounters;
    private final Map<TossApiGroup, Counter> rejectedCounters;

    @Autowired
    public TossRateLimiterRegistry(MeterRegistry meterRegistry) {
        this(defaultGates(), meterRegistry);
    }

    public TossRateLimiterRegistry() {
        this(defaultGates(), new SimpleMeterRegistry());
    }

    /** 테스트용 - fake 시각/대기가 주입된 gate 맵을 받는다. */
    TossRateLimiterRegistry(Map<TossApiGroup, FixedIntervalGate> gates) {
        this(gates, new SimpleMeterRegistry());
    }

    TossRateLimiterRegistry(Map<TossApiGroup, FixedIntervalGate> gates, MeterRegistry meterRegistry) {
        this.gates = gates;
        this.waitTimers = new EnumMap<>(TossApiGroup.class);
        this.acquiredCounters = new EnumMap<>(TossApiGroup.class);
        this.rejectedCounters = new EnumMap<>(TossApiGroup.class);
        for (TossApiGroup group : TossApiGroup.values()) {
            // gate 가 없는 맵(테스트)일 수 있으므로 존재하는 그룹만 계측한다.
            if (!gates.containsKey(group)) continue;
            waitTimers.put(group, Timer.builder("toss.ratelimiter.wait")
                    .description("Toss API 그룹별 rate limiter 대기 시간")
                    .tag("group", group.name())
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            acquiredCounters.put(group, Counter.builder("toss.ratelimiter.acquired")
                    .description("Toss API 그룹별 permit 획득 수")
                    .tag("group", group.name())
                    .register(meterRegistry));
            rejectedCounters.put(group, Counter.builder("toss.ratelimiter.rejected")
                    .description("Toss API 그룹별 tryAcquire 거절 수")
                    .tag("group", group.name())
                    .register(meterRegistry));
            // 그룹별 TPS 상한(정적)을 gauge 로 노출한다 — 대시보드에서 여유(=상한-소비) 계산용.
            Gauge.builder("toss.ratelimiter.limit.tps", group, TossApiGroup::tps)
                    .description("Toss API 그룹별 TPS 상한")
                    .tag("group", group.name())
                    .register(meterRegistry);
        }
    }

    public void acquire(TossApiGroup group) {
        long start = System.nanoTime();
        gates.get(group).acquire();
        record(waitTimers.get(group), System.nanoTime() - start);
        increment(acquiredCounters.get(group));
    }

    public boolean tryAcquire(TossApiGroup group) {
        boolean acquired = gates.get(group).tryAcquire();
        increment(acquired ? acquiredCounters.get(group) : rejectedCounters.get(group));
        return acquired;
    }

    private static void record(Timer timer, long nanos) {
        if (timer != null) timer.record(nanos, TimeUnit.NANOSECONDS);
    }

    private static void increment(Counter counter) {
        if (counter != null) counter.increment();
    }

    private static Map<TossApiGroup, FixedIntervalGate> defaultGates() {
        Map<TossApiGroup, FixedIntervalGate> gates = new EnumMap<>(TossApiGroup.class);
        for(TossApiGroup group : TossApiGroup.values()) {
            gates.put(group, new FixedIntervalGate(group.tps()));
        }
        return gates;
    }
}
