package com.baedang.trading.repository;

import com.baedang.trading.entity.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {

    Optional<TradeOrder> findByClientOrderId(UUID clientOrderId);

    long countByAccountId(Long accountId);
}
