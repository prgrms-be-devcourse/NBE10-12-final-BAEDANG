package com.baedang.global.clients.toss;


import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Toss API 그룹별 gate를 보관하는 싱글톤.
 * 스케줄러와 사용자 요청이 같은 그룹이면 같은 gate를 공유해 호출량이 합산된다.
 */
@Component
public class TossRateLimiterRegistry {

    private final Map<TossApiGroup, FixedIntervalGate> gates;

    public TossRateLimiterRegistry() {
        this(defaultGates());
    }

    /** 테스트용 - fake 시각/대기가 주입된 gate 맵을 받는다. */
    TossRateLimiterRegistry(Map<TossApiGroup, FixedIntervalGate> gates) {
        this.gates = gates;
    }

    public void acquire(TossApiGroup group) {
        gates.get(group).acquire();
    }

    public boolean tryAcquire(TossApiGroup group) {
        return gates.get(group).tryAcquire();
    }

    private static Map<TossApiGroup, FixedIntervalGate> defaultGates() {
        Map<TossApiGroup, FixedIntervalGate> gates = new EnumMap<>(TossApiGroup.class);
        for(TossApiGroup group : TossApiGroup.values()) {
            gates.put(group, new FixedIntervalGate(group.tps()));
        }
        return gates;
    }
}
