package com.baedang.market.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** 단일 인스턴스에서 같은 종목의 일봉 조회부터 저장 커밋까지 순서대로 실행한다. */
@Component
public class DailyCandleFetchCoordinator {
    private final ReentrantLock[] locks = IntStream.range(0, 64)
            .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    @Transactional(propagation = Propagation.NEVER)
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
