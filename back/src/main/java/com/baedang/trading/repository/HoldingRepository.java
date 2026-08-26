package com.baedang.trading.repository;

import com.baedang.trading.entity.Holding;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    Optional<Holding> findByAccountIdAndStockId(Long accountId, Long stockId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Holding h where h.accountId = :accountId and h.stockId = :stockId")
    Optional<Holding> findByAccountIdAndStockIdForUpdate(
            @Param("accountId") Long accountId,
            @Param("stockId") Long stockId
    );

}
