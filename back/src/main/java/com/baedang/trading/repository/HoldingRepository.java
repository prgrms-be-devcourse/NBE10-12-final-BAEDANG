package com.baedang.trading.repository;

import com.baedang.trading.entity.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    Optional<Holding> findByAccountIdAndStockId(Long accountId, Long stockId);

    /** 계좌의 보유 종목 중 수량이 남은 것만. 마이페이지 평가에 씁니다. */
    List<Holding> findByAccountIdAndQuantityGreaterThan(Long accountId, BigDecimal quantity);
}
