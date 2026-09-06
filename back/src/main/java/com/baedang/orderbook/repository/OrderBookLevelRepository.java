package com.baedang.orderbook.repository;

import com.baedang.orderbook.entity.OrderBookLevel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderBookLevelRepository extends JpaRepository<OrderBookLevel, Long> {

    long countByBookVersion_BookVersionId(Long bookVersionId);
}
