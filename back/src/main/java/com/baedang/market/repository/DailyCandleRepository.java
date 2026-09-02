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

    /**
     * 주어진 종목 중 <b>일봉 이력이 하나라도 있는</b> 종목 ID 집합.
     *
     * <p>시드 백필의 효율 가드용입니다. 정기 수집의 {@link #findStoredStockIds}가
     * 특정 거래일 기준인 것과 달리, 여기서는 "아무 날짜라도 봉이 있는가"를 봅니다.
     * 여기 포함되지 않은 종목만 200일치를 새로 수집합니다.
     */
    @Query("""
            SELECT DISTINCT candle.stockId
            FROM DailyCandle candle
            WHERE candle.stockId IN :stockIds
            """)
    Set<Long> findStockIdsWithAnyCandle(@Param("stockIds") List<Long> stockIds);
}
