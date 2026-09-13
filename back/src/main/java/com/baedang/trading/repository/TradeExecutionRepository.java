package com.baedang.trading.repository;

import com.baedang.trading.entity.TradeExecution;
import com.baedang.trading.model.CostReplayEvent;
import com.baedang.trading.model.CumulativeSettlementState;
import com.baedang.trading.model.HoldingReplayEvent;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import java.util.Collection;
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

    /**
     * 계좌의 특정 종목들에 대한 <b>개별 체결</b>을 체결 시각 오름차순으로 조회합니다.
     * 방향은 주문이 소유하므로 {@code trade_order} 를 조인합니다. 보유 lot 시작 시점을 체결
     * 단위로 재생할 때 씁니다({@link HoldingReplayEvent}). 시각 동률은 {@code executionId} 로
     * 안정 정렬해 접수 순서가 아닌 실제 체결 순서를 보존합니다.
     */
    @Query("select new com.baedang.trading.model.HoldingReplayEvent("
            + "o.stockId, o.side, e.quantity, e.executedAt)"
            + " from TradeExecution e join TradeOrder o on e.orderId = o.orderId"
            + " where o.accountId = :accountId and o.stockId in :stockIds"
            + " order by e.executedAt asc, e.executionId asc")
    List<HoldingReplayEvent> findHoldingReplayEvents(
            @Param("accountId") Long accountId, @Param("stockIds") Collection<Long> stockIds);

    /**
     * 계좌의 모든 개별 체결을 원가 재생용으로 조회합니다(체결 시각 오름차순). 창 시작 시점의 원가
     * 구성을 복원하려면 창 이전 체결까지 필요하므로 시각으로 자르지 않습니다. 창 안에서 전량
     * 매도된 종목도 포함하려고 종목 필터도 두지 않습니다(투자 MBTI 원가 4주 평균, 설계문서 §6.2).
     */
    @Query("select new com.baedang.trading.model.CostReplayEvent("
            + "o.stockId, o.side, e.quantity, e.grossAmountKrw, e.executedAt)"
            + " from TradeExecution e join TradeOrder o on e.orderId = o.orderId"
            + " where o.accountId = :accountId"
            + " order by e.executedAt asc, e.executionId asc")
    List<CostReplayEvent> findCostReplayEvents(@Param("accountId") Long accountId);
}
