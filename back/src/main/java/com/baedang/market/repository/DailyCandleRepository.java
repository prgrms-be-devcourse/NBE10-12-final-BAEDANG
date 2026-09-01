package com.baedang.market.repository;

import com.baedang.market.entity.DailyCandle;
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

    long countByStockId(Long stockId);

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
