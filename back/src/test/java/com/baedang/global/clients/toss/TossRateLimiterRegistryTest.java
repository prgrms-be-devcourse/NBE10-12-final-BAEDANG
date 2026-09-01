package com.baedang.global.clients.toss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TossRateLimiterRegistryTest {
    @Test
    @DisplayName("같은 그룹은 gate를 공유하고 다른 그룹은 서로를 막지 않는다")
    void t1 () {
        AtomicLong now = new AtomicLong();
        List<Long> slept = new CopyOnWriteArrayList<>();
        Map<TossApiGroup, FixedIntervalGate> gates = new EnumMap<>(TossApiGroup.class);
        for(TossApiGroup group : TossApiGroup.values()) {
            gates.put(group, new FixedIntervalGate(group.tps(),now::get, slept::add));
        }

        TossRateLimiterRegistry registry = new TossRateLimiterRegistry(gates);

        registry.acquire(TossApiGroup.STOCK); // 대기 0
        registry.acquire(TossApiGroup.STOCK); // + 200ms
        registry.acquire(TossApiGroup.MARKET_DATA); // 대기 0 - 독립 그룹

        assertThat(slept).containsExactly(0L, TimeUnit.MILLISECONDS.toNanos(200),0L);
    }


}
