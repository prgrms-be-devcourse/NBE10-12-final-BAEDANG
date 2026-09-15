package com.baedang.trading.repository;

import com.baedang.trading.entity.Holding;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    Optional<Holding> findByAccountIdAndStockId(Long accountId, Long stockId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Holding h where h.accountId = :accountId and h.stockId = :stockId")
    Optional<Holding> findByAccountIdAndStockIdForUpdate(
            @Param("accountId") Long accountId,
            @Param("stockId") Long stockId
    );

    /** 계좌의 보유 종목 중 수량이 남은 것만. 마이페이지 평가에 씁니다. */
    List<Holding> findByAccountIdAndQuantityGreaterThan(Long accountId, BigDecimal quantity);

    /** 지정가 주문 도입 후 초기화가 동결 수량을 남기지 않도록 방어합니다. */
    boolean existsByAccountIdAndLockedQuantityGreaterThan(Long accountId, BigDecimal lockedQuantity);
}
