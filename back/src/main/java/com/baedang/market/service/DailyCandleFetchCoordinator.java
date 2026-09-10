package com.baedang.market.service;

import org.springframework.stereotype.Component;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** Single-instance serialization of each daily-candle fetch and its committed write. */
@Component
public class DailyCandleFetchCoordinator {
    private final ReentrantLock[] locks = IntStream.range(0, 64)
            .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public <T> T withStockLock(Long stockId, Supplier<T> operation) {
        ReentrantLock lock = locks[Long.hashCode(stockId) & 63];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }
}
