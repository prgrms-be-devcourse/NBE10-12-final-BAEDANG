package com.baedang.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 주문 + 체결. {@code order} 는 SQL 예약어라 테이블명이 {@code trade_order} 입니다.
 *
 * <p><b>시장가는 PENDING 을 거치지 않습니다.</b> 하나의 트랜잭션에서 즉시
 * 체결되므로 FILLED 또는 REJECTED 로 INSERT 합니다. PENDING 은 지정가 주문의
 * 접수·동결 트랜잭션부터 사용합니다.
 *
 * <p>fee·tax는 확정 금액입니다. 요율은 프로젝트의 고정 환경 설정을 사용하며 주문에 저장하지 않습니다.
 * 개별 체결 근거는 TradeExecution에 보존하며 LIMIT의 금액은 체결 차액의 누계입니다.
 */
@Entity
@Table(
        name = "trade_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_account_client_order",
                columnNames = {"account_id", "client_order_id"}
        )
)
public class TradeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    /**
     * 멱등성 키. 프론트가 주문 화면 진입 시 생성해 함께 보냅니다.
     * 버튼을 두 번 눌러도 계좌+멱등 키 UNIQUE 제약에 걸려 중복 체결이 막힙니다.
     */
    @Column(name = "client_order_id", nullable = false)
    private UUID clientOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 4)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private OrderType orderType;

    /** 주문 수량. NUMERIC으로 저장하며 허용 주문 단위는 주문 정책에서 검증합니다. */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    /** ErrorCode 이름을 그대로 넣습니다 — INSUFFICIENT_CASH, STALE_QUOTE 등. */
    @Column(name = "reject_reason", length = 40)
    private String rejectReason;

    /** REJECTED 판정에 사용한 기준 가격. FILLED 주문은 {@code null}입니다. */
    @Column(name = "reference_price", precision = 19, scale = 4)
    private BigDecimal referencePrice;

    /** 체결 단가. <b>종목 통화 기준</b>입니다 (미국이면 달러). */
    @Column(name = "executed_price", precision = 19, scale = 4)
    private BigDecimal executedPrice;

    /**
     * 체결 또는 거절 판정에 쓴 시세의 기준 시각.
     * REJECTED 주문은 {@link #referencePrice}와 함께 감사 근거로 보존합니다.
     */
    @Column(name = "quote_at")
    private OffsetDateTime quoteAt;

    /** 체결 또는 거절 판정 시점 환율. 원화 종목은 1입니다. */
    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    /** MARKET의 단일 체결 금액 또는 LIMIT의 체결별 정산 금액 누계(원화)입니다. */
    @Column(name = "gross_amount", precision = 19, scale = 4)
    private BigDecimal grossAmount;

    /** 적용된 수수료 <b>금액</b>. 요율이 아닙니다. */
    @Column(name = "fee", precision = 19, scale = 4)
    private BigDecimal fee;

    /** 적용된 세금 <b>금액</b>. 국내는 증권거래세, 미국은 SEC Fee. 매수는 0. */
    @Column(name = "tax", precision = 19, scale = 4)
    private BigDecimal tax;

    /** 실제 예수금 증감액. 매수 = gross+fee, 매도 = gross−fee−tax. */
    @Column(name = "net_amount", precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "ordered_at", nullable = false)
    private OffsetDateTime orderedAt;

    @Column(name = "limit_price", precision = 19, scale = 4)
    private BigDecimal limitPrice;
    @Column(name = "filled_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal filledQuantity = BigDecimal.ZERO;
    @Column(name = "execution_count", nullable = false)
    private int executionCount;
    @Column(name = "last_executed_at")
    private OffsetDateTime lastExecutedAt;
    /** 해당 주문의 미체결 잔여분에 현재 동결된 원화 금액. 종료 시 0입니다. */
    @Column(name = "reserved_cash", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedCash = BigDecimal.ZERO;
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    protected TradeOrder() {
    }

    private TradeOrder(Long accountId, Long stockId, UUID clientOrderId,
                       OrderSide side, BigDecimal quantity, OrderStatus status,
                       OffsetDateTime orderedAt) {
        this.accountId = accountId;
        this.stockId = stockId;
        this.clientOrderId = clientOrderId;
        this.side = side;
        this.quantity = quantity;
        this.orderType = OrderType.MARKET;
        this.status = status;
        this.orderedAt = orderedAt;
    }

    /** 시장가 체결 결과를 처음부터 FILLED 상태로 생성합니다. */
    public static TradeOrder filledMarketOrder(
            Long accountId, Long stockId, UUID clientOrderId, OrderSide side,
            BigDecimal quantity, BigDecimal executedPrice, OffsetDateTime quoteAt,
            BigDecimal exchangeRate, BigDecimal grossAmount, BigDecimal fee,
            BigDecimal tax, BigDecimal netAmount, OffsetDateTime orderedAt
    ) {
        TradeOrder order = new TradeOrder(
                accountId, stockId, clientOrderId, side, quantity, OrderStatus.FILLED, orderedAt);
        order.executedPrice = executedPrice;
        order.quoteAt = quoteAt;
        order.exchangeRate = exchangeRate;
        order.grossAmount = grossAmount;
        order.fee = fee;
        order.tax = tax;
        order.netAmount = netAmount;
        order.filledQuantity = quantity;
        order.executionCount = 1;
        order.lastExecutedAt = orderedAt;
        order.closedAt = orderedAt;
        return order;
    }

    /** 유효한 시장가 요청이 업무 규칙으로 거절된 기록을 생성합니다. */
    public static TradeOrder rejectedMarketOrder(
            Long accountId, Long stockId, UUID clientOrderId, OrderSide side,
            BigDecimal quantity, BigDecimal referencePrice, OffsetDateTime quoteAt,
            BigDecimal exchangeRate, String reasonCode, OffsetDateTime orderedAt
    ) {
        TradeOrder order = new TradeOrder(
                accountId, stockId, clientOrderId, side, quantity, OrderStatus.REJECTED, orderedAt);
        order.rejectReason = reasonCode;
        order.referencePrice = referencePrice;
        order.quoteAt = quoteAt;
        order.exchangeRate = exchangeRate;
        order.closedAt = orderedAt;
        return order;
    }

    /** 금액 계산과 account/holding 동결은 호출부의 같은 트랜잭션에서 수행합니다. */
    public static TradeOrder pendingLimitOrder(Long accountId, Long stockId, UUID clientOrderId,
                                               OrderSide side, BigDecimal quantity, BigDecimal limitPrice,
                                               BigDecimal reservedCash,
                                               OffsetDateTime orderedAt,
                                               OffsetDateTime expiresAt) {
        if (accountId == null || accountId <= 0 || stockId == null || stockId <= 0 || clientOrderId == null
                || side == null || quantity == null || quantity.signum() <= 0 || limitPrice == null || limitPrice.signum() <= 0
                || reservedCash == null || reservedCash.signum() < 0 || (side == OrderSide.SELL && reservedCash.signum() != 0)
                || (side == OrderSide.BUY && reservedCash.signum() == 0)
                || orderedAt == null || expiresAt == null || !orderedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("지정가 접수 근거가 올바르지 않습니다");
        }
        reservedCash.setScale(0, java.math.RoundingMode.UNNECESSARY);
        quantity.setScale(6, java.math.RoundingMode.UNNECESSARY);
        limitPrice.setScale(4, java.math.RoundingMode.UNNECESSARY);
        TradeOrder order = new TradeOrder(accountId, stockId, clientOrderId, side, quantity, OrderStatus.PENDING, orderedAt);
        order.orderType = OrderType.LIMIT;
        order.limitPrice = limitPrice;
        order.reservedCash = reservedCash;
        order.expiresAt = expiresAt;
        order.grossAmount = BigDecimal.ZERO;
        order.fee = BigDecimal.ZERO;
        order.tax = BigDecimal.ZERO;
        order.netAmount = BigDecimal.ZERO;
        return order;
    }

    public boolean isActive() {
        return status == OrderStatus.PENDING || status == OrderStatus.PARTIALLY_FILLED;
    }

    /** 종료된 미체결 잔여분은 활성 수량에 포함하지 않습니다. */
    public BigDecimal activeRemainingQuantity() {
        return isActive() ? quantity.subtract(filledQuantity) : BigDecimal.ZERO;
    }

    /**
     * 계좌 잠금 아래 저장된 체결 한 건을 반영합니다. sequence로 이중 반영을 거절합니다.
     * 잔여 동결 계산과 계좌/보유수량 갱신은 상위 DB 서비스가 같은 트랜잭션에서 수행합니다.
     */
    public void applyExecution(TradeExecution execution, BigDecimal nextReservedCash) {
        if (orderType != OrderType.LIMIT || !isActive() || execution == null || execution.getExecutionId() == null
                || !java.util.Objects.equals(orderId, execution.getOrderId())
                || execution.getSequenceNo() != executionCount + 1
                || execution.getExecutedAt().isBefore(orderedAt) || !execution.getExecutedAt().isBefore(expiresAt)
                || (lastExecutedAt != null && execution.getExecutedAt().isBefore(lastExecutedAt))
                || (side == OrderSide.BUY ? execution.getPrice().compareTo(limitPrice) > 0
                                         : execution.getPrice().compareTo(limitPrice) < 0)
                || nextReservedCash == null || nextReservedCash.signum() < 0
                || nextReservedCash.compareTo(reservedCash) > 0) {
            throw new IllegalArgumentException("체결 반영 순서/대상/잔여 동결액이 올바르지 않습니다");
        }
        execution.validateOrder(this);
        BigDecimal nextFilled = filledQuantity.add(execution.getQuantity());
        if (nextFilled.compareTo(quantity) > 0 || (nextFilled.compareTo(quantity) == 0 && nextReservedCash.signum() != 0)
                || (side == OrderSide.SELL && nextReservedCash.signum() != 0)) {
            throw new IllegalArgumentException("체결 수량 또는 종료 동결액이 올바르지 않습니다");
        }
        nextReservedCash.setScale(0, java.math.RoundingMode.UNNECESSARY);
        filledQuantity = nextFilled;
        executionCount = execution.getSequenceNo();
        lastExecutedAt = execution.getExecutedAt();
        reservedCash = nextReservedCash;
        grossAmount = grossAmount.add(execution.getGrossAmountKrw());
        fee = fee.add(execution.getFeeKrw());
        tax = tax.add(execution.getTaxKrw());
        netAmount = netAmount.add(execution.getNetAmountKrw());
        status = nextFilled.compareTo(quantity) == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        if (status == OrderStatus.FILLED) closedAt = execution.getExecutedAt();
    }

    public boolean cancel(OffsetDateTime at) { return closeRemainder(OrderStatus.CANCELED, at); }
    public boolean expire(OffsetDateTime at) { return closeRemainder(OrderStatus.EXPIRED, at); }

    private boolean closeRemainder(OrderStatus target, OffsetDateTime at) {
        if (orderType != OrderType.LIMIT) throw new IllegalStateException("지정가 잔여분만 종료할 수 있습니다");
        if (status == target) return false;
        if (!isActive() || at == null || at.isBefore(orderedAt)
                || (lastExecutedAt != null && at.isBefore(lastExecutedAt))
                || (target == OrderStatus.EXPIRED ? at.isBefore(expiresAt) : !at.isBefore(expiresAt))) {
            throw new IllegalStateException("주문 상태 또는 종료 시각이 올바르지 않습니다");
        }
        status = target;
        reservedCash = BigDecimal.ZERO;
        closedAt = at;
        return true;
    }

    public Long getOrderId() { return orderId; }
    public Long getAccountId() { return accountId; }
    public Long getStockId() { return stockId; }
    public UUID getClientOrderId() { return clientOrderId; }
    public OrderSide getSide() { return side; }
    public OrderType getOrderType() { return orderType; }
    public BigDecimal getQuantity() { return quantity; }
    public OrderStatus getStatus() { return status; }
    public String getRejectReason() { return rejectReason; }
    public BigDecimal getReferencePrice() { return referencePrice; }
    public BigDecimal getExecutedPrice() { return executedPrice; }
    public OffsetDateTime getQuoteAt() { return quoteAt; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getTax() { return tax; }
    public BigDecimal getNetAmount() { return netAmount; }
    public OffsetDateTime getOrderedAt() { return orderedAt; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public BigDecimal getFilledQuantity() { return filledQuantity; }
    public int getExecutionCount() { return executionCount; }
    public OffsetDateTime getLastExecutedAt() { return lastExecutedAt; }
    public BigDecimal getReservedCash() { return reservedCash; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
}
