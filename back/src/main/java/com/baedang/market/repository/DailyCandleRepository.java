package com.baedang.market.repository;

import com.baedang.market.entity.DailyCandle;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DailyCandleRepository extends JpaRepository<DailyCandle, DailyCandle.Pk> {

    List<DailyCandle> findByStockIdOrderByTradeDateDesc(Long stockId, Pageable pageable);

    /** 해당 종목의 일봉 존재 여부 확인 (초기 백필 대상 선별용) */
    boolean existsByStockId(Long stockId);
}
