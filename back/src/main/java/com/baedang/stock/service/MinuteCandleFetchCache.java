package com.baedang.stock.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MinuteCandleFetchCache {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final ConcurrentHashMap<Long, Instant> fetchedAtByStockId = new ConcurrentHashMap<>();

    public boolean isFresh(Long stockId, Instant now) {
        Instant fetchedAt = fetchedAtByStockId.get(stockId);
        return fetchedAt != null
                && !fetchedAt.isAfter(now)
                && fetchedAt.plus(TTL).isAfter(now);
    }

    public void markFetched(Long stockId, Instant fetchedAt) {
        fetchedAtByStockId.put(stockId, fetchedAt);
    }
}
