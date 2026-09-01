package com.baedang.market.repository;

import com.baedang.market.entity.DailyCandle;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DailyCandleRepository extends JpaRepository<DailyCandle, DailyCandle.Pk> {

    List<DailyCandle> findByStockIdOrderByTradeDateDesc(Long stockId, Pageable pageable);

    /** 전체 개수를 세지 않고 필요한 마지막 위치의 행 하나만 조회해 최소 이력 충족 여부를 확인한다. */
    default boolean hasAtLeastCandles(Long stockId, int requiredCount) {
        if (requiredCount <= 0) return true;
        return !findByStockIdOrderByTradeDateDesc(
                stockId,
                PageRequest.of(requiredCount - 1, 1)
        ).isEmpty();
    }

    Optional<DailyCandle> findTopByStockIdOrderByTradeDateDesc(Long stockId);

    @Query("""
            SELECT candle.stockId
            FROM DailyCandle candle
            WHERE candle.tradeDate = :tradeDate
              AND candle.stockId IN :stockIds
            """)
    Set<Long> findStoredStockIds(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("stockIds") List<Long> stockIds
    );
}
