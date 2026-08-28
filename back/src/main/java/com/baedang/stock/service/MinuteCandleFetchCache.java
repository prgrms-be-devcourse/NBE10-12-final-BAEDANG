package com.baedang.stock.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MinuteCandleFetchCache {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int CLEANUP_INTERVAL = 64;

    private final ConcurrentHashMap<Long, Instant> fetchedAtByStockId = new ConcurrentHashMap<>();
    private final AtomicInteger writesSinceCleanup = new AtomicInteger();

    public boolean isFresh(Long stockId, Instant now) {
        Instant fetchedAt = fetchedAtByStockId.get(stockId);
        if (isFreshAt(fetchedAt, now)) return true;
        if (fetchedAt != null) fetchedAtByStockId.remove(stockId, fetchedAt);
        return false;
    }

    public void markFetched(Long stockId, Instant fetchedAt) {
        fetchedAtByStockId.put(stockId, fetchedAt);
        if (writesSinceCleanup.incrementAndGet() >= CLEANUP_INTERVAL) {
            writesSinceCleanup.set(0);
            evictExpired(fetchedAt);
        }
    }

    void evictExpired(Instant now) {
        fetchedAtByStockId.entrySet().removeIf(entry -> !isFreshAt(entry.getValue(), now));
    }

    int entryCount() {
        return fetchedAtByStockId.size();
    }

    private boolean isFreshAt(Instant fetchedAt, Instant now) {
        return fetchedAt != null
                && !fetchedAt.isAfter(now)
                && fetchedAt.plus(TTL).isAfter(now);
    }
}
