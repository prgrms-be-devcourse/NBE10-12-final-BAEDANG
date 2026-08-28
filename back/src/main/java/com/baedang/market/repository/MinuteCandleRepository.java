package com.baedang.market.repository;

import com.baedang.market.entity.MinuteCandle;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MinuteCandleRepository extends JpaRepository<MinuteCandle, MinuteCandle.Pk> {

    List<MinuteCandle> findByStockIdOrderByCandleAtDesc(Long stockId, Pageable pageable);

    Optional<MinuteCandle> findTopByStockIdOrderByCandleAtDesc(Long stockId);
}
