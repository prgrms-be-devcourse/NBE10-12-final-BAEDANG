package com.baedang.trading.repository;

import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.TradeOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from TradeOrder o where o.orderId = :id")
    Optional<TradeOrder> findForUpdate(@Param("id") Long id);

    @Query("select o from TradeOrder o where o.accountId = :accountId and o.orderId < :before order by o.orderId desc")
    List<TradeOrder> history(@Param("accountId") Long accountId,
            @Param("before") Long before, Pageable page);

    @Query("select o from TradeOrder o where o.status in :statuses and o.expiresAt <= :now and o.orderId > :after order by o.orderId")
    List<TradeOrder> expired(
            @Param("now") OffsetDateTime now,
            @Param("after") Long after,
            @Param("statuses") Collection<OrderStatus> statuses,
            Pageable page);

    default List<TradeOrder> expired(OffsetDateTime now, Long after, Pageable page) {
        return expired(now, after, List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED), page);
    }


    Optional<TradeOrder> findByAccountIdAndClientOrderId(Long accountId, UUID clientOrderId);

    /**
     * 계좌의 특정 종목들에 대해 <b>실제로 체결된</b> 주문을 시간 오름차순으로 조회합니다.
     * 보유 lot 의 시작 시점을 재생(replay)으로 찾을 때 씁니다({@code filled_quantity > 0} 이라
     * REJECTED·미체결은 제외). 시각 동률은 orderId 로 안정 정렬합니다.
     */
    @Query("select o from TradeOrder o where o.accountId = :accountId and o.stockId in :stockIds"
            + " and o.filledQuantity > 0 order by o.orderedAt asc, o.orderId asc")
    List<TradeOrder> findFilledByAccountAndStocks(
            @Param("accountId") Long accountId, @Param("stockIds") Collection<Long> stockIds);

    long countByAccountId(Long accountId);

    /** 원장 항목들의 종목명 조인을 위해 orderId → stockId 를 한 번에 조회합니다. */
    List<TradeOrder> findByOrderIdIn(Collection<Long> orderIds);
}
