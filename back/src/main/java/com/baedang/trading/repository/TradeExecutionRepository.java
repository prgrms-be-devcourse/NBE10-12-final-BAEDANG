package com.baedang.trading.repository;

import com.baedang.trading.entity.TradeExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import java.util.Optional;
import java.util.UUID;

/** 확정 체결도 append-only로 사용하므로 저장/조회만 노출합니다. */
public interface TradeExecutionRepository extends Repository<TradeExecution, Long> {
    TradeExecution save(TradeExecution execution);
    Optional<TradeExecution> findById(Long executionId);
    Optional<TradeExecution> findByOrderIdAndExecutionKey(Long orderId, UUID executionKey);
    Page<TradeExecution> findByOrderIdOrderBySequenceNoAsc(Long orderId, Pageable pageable);
    long countByOrderId(Long orderId);
}
