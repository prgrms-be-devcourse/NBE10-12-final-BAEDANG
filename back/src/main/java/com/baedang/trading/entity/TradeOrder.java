package com.baedang.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 주문 + 체결. {@code order} 는 SQL 예약어라 테이블명이 {@code trade_order} 입니다.
 *
 * <p><b>1주차 시장가는 PENDING 을 거치지 않습니다.</b> 접수와 체결이 한 트랜잭션
 * 안에서 연속 실행되므로 FILLED 또는 REJECTED 로 직행합니다.
 *
 * <p><b>요율은 저장하지 않습니다.</b> {@code fee}·{@code tax} 에는 계산된 <b>금액</b>이
 * 들어갑니다. 요율은 전역 정책이라 {@code application.yml} 의 {@code trading.*} 이
 * 유일한 정의 지점입니다. 금액이 남아 있으면 요율이 바뀌어도 과거 거래가 안 흔들립니다.
 */
@Entity
@Table(name = "trade_order")
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
     * 버튼을 두 번 눌러도 UNIQUE 제약에 걸려 중복 체결이 막힙니다.
     */
    @Column(name = "client_order_id", nullable = false, unique = true)
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

    /** 체결 단가. <b>종목 통화 기준</b>입니다 (미국이면 달러). */
    @Column(name = "executed_price", precision = 19, scale = 4)
    private BigDecimal executedPrice;

    /**
     * 체결에 쓴 시세의 기준 시각.
     * "왜 이 가격에 체결됐는가"를 설명하는 유일한 근거이고,
     * 금융 도메인에서 가장 중요한 감사 항목입니다.
     */
    @Column(name = "quote_at")
    private OffsetDateTime quoteAt;

    /** 체결 시점 환율. 원화 종목은 1. 안 남기면 환차손익을 영원히 분리할 수 없습니다. */
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
                       OrderSide side, BigDecimal quantity) {
        this.accountId = accountId;
        this.stockId = stockId;
        this.clientOrderId = clientOrderId;
        this.side = side;
        this.quantity = quantity;
        this.orderType = OrderType.MARKET;
        this.status = OrderStatus.PENDING;
        this.orderedAt = OffsetDateTime.now();
    }

    /** 시장가 주문 접수. 1주차에는 이 상태가 커밋되지 않고 곧바로 체결로 넘어갑니다. */
    public static TradeOrder placeMarketOrder(Long accountId, Long stockId, UUID clientOrderId,
                                              OrderSide side, BigDecimal quantity) {
        return new TradeOrder(accountId, stockId, clientOrderId, side, quantity);
    }

    /** 체결 확정. 시장가는 접수 직후 곧바로 이 메서드로 넘어옵니다. */
    public void fill(BigDecimal executedPrice, OffsetDateTime quoteAt, BigDecimal exchangeRate,
                     BigDecimal grossAmount, BigDecimal fee, BigDecimal tax, BigDecimal netAmount) {
        this.executedPrice = executedPrice;
        this.quoteAt = quoteAt;
        this.exchangeRate = exchangeRate;
        this.grossAmount = grossAmount;
        this.fee = fee;
        this.tax = tax;
        this.netAmount = netAmount;
        this.status = OrderStatus.FILLED;
    }

    /** 검증 단계 거절. 자금을 동결하지 않았으므로 되돌릴 것이 없습니다. */
    public void reject(String reasonCode) {
        this.status = OrderStatus.REJECTED;
        this.rejectReason = reasonCode;
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
    public BigDecimal getExecutedPrice() { return executedPrice; }
    public OffsetDateTime getQuoteAt() { return quoteAt; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getTax() { return tax; }
    public BigDecimal getNetAmount() { return netAmount; }
    public OffsetDateTime getOrderedAt() { return orderedAt; }
}
