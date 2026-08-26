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
 * <p><b>요율은 저장하지 않습니다.</b> {@code fee}·{@code tax} 에는 계산된 <b>금액</b>이
 * 들어갑니다. 요율은 전역 정책이라 {@code application.yml} 의 {@code trading.*} 이
 * 유일한 정의 지점입니다. 금액이 남아 있으면 요율이 바뀌어도 과거 거래가 안 흔들립니다.
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

    /** 1주차는 정수만 받습니다. NUMERIC 인 건 2주차 소수점 주문 대비입니다. */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
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

    /** 체결 금액(원화 환산) = executedPrice × quantity × exchangeRate. */
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
        return order;
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
}
