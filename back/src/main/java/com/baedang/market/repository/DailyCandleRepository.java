package com.baedang.market.repository;

import com.baedang.market.entity.DailyCandle;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DailyCandleRepository extends JpaRepository<DailyCandle, DailyCandle.Pk> {

    List<DailyCandle> findByStockIdOrderByTradeDateDesc(Long stockId, Pageable pageable);

}
