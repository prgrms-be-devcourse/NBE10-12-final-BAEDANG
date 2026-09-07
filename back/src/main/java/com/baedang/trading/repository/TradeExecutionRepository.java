package com.baedang.trading.repository;

import com.baedang.trading.entity.TradeExecution;
import com.baedang.trading.model.CumulativeSettlementState;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 확정 체결도 append-only로 사용하므로 저장/조회만 노출합니다. */
public interface TradeExecutionRepository extends Repository<TradeExecution, Long> {
    TradeExecution save(TradeExecution execution);
    Optional<TradeExecution> findById(Long executionId);
    Optional<TradeExecution> findByOrderIdAndExecutionKey(Long orderId, UUID executionKey);
    Page<TradeExecution> findByOrderIdOrderBySequenceNoAsc(Long orderId, Pageable pageable);
    long countByOrderId(Long orderId);
    List<TradeExecution> findByOrderIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
            Long orderId, int sequenceNo, Pageable page);

    /**
     * 주문별 누적 근거를 한 쿼리로 복원합니다. 실제 체결은 계좌 잠금 후 읽고, 후속 후보는 메모리에서 누적합니다.
     * 5번째 필드(unroundedTaxKrw)는 SUM(secFeeUsd × exchangeRate)로 복원합니다.
     * KR과 매수는 secFeeUsd가 항상 0이므로 결과도 0이며,
     * KR 매도 세금은 LimitOrderSettlementCalculator.validateState()에서 gross × krSellTaxRate로 별도 검증합니다.
     */
    @Query("""
            select new com.baedang.trading.model.CumulativeSettlementState(
                coalesce(sum(e.quantity), 0), coalesce(sum(e.price * e.quantity), 0),
                coalesce(sum(e.price * e.quantity * e.exchangeRate), 0), coalesce(sum(e.secFeeUsd), 0),
                coalesce(sum(e.secFeeUsd * e.exchangeRate), 0), coalesce(sum(e.grossAmountKrw), 0),
                coalesce(sum(e.feeKrw), 0), coalesce(sum(e.taxKrw), 0))
            from TradeExecution e where e.orderId = :orderId
            """)
    CumulativeSettlementState summarizeByOrderId(@Param("orderId") Long orderId);
}
