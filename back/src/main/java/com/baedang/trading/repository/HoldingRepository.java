package com.baedang.trading.repository;

import com.baedang.trading.entity.Holding;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    Optional<Holding> findByAccountIdAndStockId(Long accountId, Long stockId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Holding h where h.accountId = :accountId and h.stockId = :stockId")
    Optional<Holding> findByAccountIdAndStockIdForUpdate(
            @Param("accountId") Long accountId,
            @Param("stockId") Long stockId
    );

    /** 응답의 최신 보유자산 평가액을 원화로 계산합니다. */
    @Query(value = """
            SELECT COALESCE(ROUND(SUM(
                CASE WHEN s.market_country = 'US'
                     THEN h.quantity * q.last_price * :usdKrwRate
                     ELSE h.quantity * q.last_price
                END
            ), 0), 0)
            FROM holding h
            JOIN stock s ON s.stock_id = h.stock_id
            JOIN quote_snapshot q ON q.stock_id = h.stock_id
            WHERE h.account_id = :accountId
            """, nativeQuery = true)
    BigDecimal calculateStockValue(
            @Param("accountId") Long accountId,
            @Param("usdKrwRate") BigDecimal usdKrwRate
    );

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM holding h
                JOIN stock s ON s.stock_id = h.stock_id
                WHERE h.account_id = :accountId
                  AND h.quantity > 0
                  AND s.market_country = 'US'
            )
            """, nativeQuery = true)
    boolean existsUsHolding(@Param("accountId") Long accountId);
}
