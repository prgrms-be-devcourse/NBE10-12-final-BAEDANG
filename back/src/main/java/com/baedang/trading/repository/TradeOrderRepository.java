package com.baedang.trading.repository;

import com.baedang.trading.entity.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {

    Optional<TradeOrder> findByAccountIdAndClientOrderId(Long accountId, UUID clientOrderId);

    long countByAccountId(Long accountId);

    /** 원장 항목들의 종목명 조인을 위해 orderId → stockId 를 한 번에 조회합니다. */
    List<TradeOrder> findByOrderIdIn(Collection<Long> orderIds);
}
