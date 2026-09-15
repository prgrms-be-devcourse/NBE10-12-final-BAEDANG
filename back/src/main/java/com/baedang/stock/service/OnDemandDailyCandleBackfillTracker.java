package com.baedang.stock.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실행 중 성공한 온디맨드 일봉 백필을 종목별로 기록한다.
 * 신규 상장처럼 전체 이력이 200개보다 적어도 같은 실행 중에는 다시 호출하지 않는다.
 * 정기 일봉 적재 및 애플리케이션 시작 시 수행하는 스케줄러 백필 상태와는 무관하다.
 */
@Component
public class OnDemandDailyCandleBackfillTracker {

    private final Set<Long> initialBackfillCompletedStockIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, LocalDate> refreshedThroughByStockId = new ConcurrentHashMap<>();

    public boolean isInitialBackfillCompleted(Long stockId) {
        return initialBackfillCompletedStockIds.contains(stockId);
    }

    public void markInitialBackfillCompleted(Long stockId) {
        initialBackfillCompletedStockIds.add(stockId);
    }

    /** 같은 확정 거래일을 대상으로 성공한 최신화 요청을 반복하지 않도록 기록한다. */
    public boolean wasRefreshedThrough(Long stockId, LocalDate expectedTradeDate) {
        LocalDate refreshedThrough = refreshedThroughByStockId.get(stockId);
        return refreshedThrough != null && !refreshedThrough.isBefore(expectedTradeDate);
    }

    public void markRefreshedThrough(Long stockId, LocalDate expectedTradeDate) {
        refreshedThroughByStockId.merge(
                stockId,
                expectedTradeDate,
                (existing, requested) -> existing.isAfter(requested) ? existing : requested
        );
    }

    int entryCount() {
        return initialBackfillCompletedStockIds.size();
    }
}
