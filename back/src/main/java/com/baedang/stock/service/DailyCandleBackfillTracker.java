package com.baedang.stock.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실행 중 성공한 일봉 과거 데이터 백필을 종목별로 기록한다.
 * 신규 상장처럼 전체 이력이 200개보다 적어도 같은 실행 중에는 다시 호출하지 않는다.
 */
@Component
public class DailyCandleBackfillTracker {

    private final Set<Long> completedStockIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, LocalDate> refreshedThroughByStockId = new ConcurrentHashMap<>();

    public boolean isCompleted(Long stockId) {
        return completedStockIds.contains(stockId);
    }

    public void markCompleted(Long stockId) {
        completedStockIds.add(stockId);
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
        return completedStockIds.size();
    }
}
