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
    void t1() {
        AtomicLong now = new AtomicLong();
        Map<TossApiGroup, List<Long>> sleptByGroup =
                new EnumMap<>(TossApiGroup.class);
        Map<TossApiGroup, FixedIntervalGate> gates =
                new EnumMap<>(TossApiGroup.class);

        for (TossApiGroup group : TossApiGroup.values()) {
            List<Long> slept = new CopyOnWriteArrayList<>();
            sleptByGroup.put(group, slept);
            gates.put(group, new FixedIntervalGate(
                            group.tps(),
                            now::get,
                            slept::add
                    )
            );
        }

        TossRateLimiterRegistry registry = new TossRateLimiterRegistry(gates);
        registry.acquire(TossApiGroup.STOCK);
        registry.acquire(TossApiGroup.STOCK);

        registry.acquire(TossApiGroup.RANKING);
        registry.acquire(TossApiGroup.RANKING);

        registry.acquire(TossApiGroup.STOCK_ALL);
        registry.acquire(TossApiGroup.STOCK_ALL);

        assertThat(sleptByGroup.get(TossApiGroup.STOCK))
                .containsExactly(
                        0L,
                        TimeUnit.MILLISECONDS.toNanos(200)
                );

        assertThat(sleptByGroup.get(TossApiGroup.RANKING))
                .containsExactly(
                        0L,
                        TimeUnit.MILLISECONDS.toNanos(200)
                );

        assertThat(sleptByGroup.get(TossApiGroup.STOCK_ALL))
                .containsExactly(
                        0L,
                        TimeUnit.SECONDS.toNanos(1)
                );
    }


}
