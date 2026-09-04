package com.baedang.trading.entity;

import com.baedang.trading.model.ExecutionAmounts;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.OrderAmount;
import com.baedang.stock.entity.MarketCountry;
import jakarta.persistence.*;

import static com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 확정된 개별 체결. 수정/삭제하지 않으며 주문 요약이나 최신 환율로 재계산하지 않습니다. */
@Entity
@Table(name = "trade_execution", uniqueConstraints = {
        @UniqueConstraint(name = "uq_execution_key", columnNames = {"order_id", "execution_key"}),
        @UniqueConstraint(name = "uq_execution_sequence", columnNames = {"order_id", "sequence_no"})
})
public class TradeExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "execution_id")
    private Long executionId;
    @Column(name = "order_id", nullable = false)
    private Long orderId;
    @Column(name = "execution_key", nullable = false)
    private UUID executionKey;
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;
    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;
    @Column(name = "exchange_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal exchangeRate;
    @Column(name = "sec_fee_usd", nullable = false, columnDefinition = "numeric")
    private BigDecimal secFeeUsd;
    @Column(name = "gross_amount_krw", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmountKrw;
    @Column(name = "fee_krw", nullable = false, precision = 19, scale = 4)
    private BigDecimal feeKrw;
    @Column(name = "tax_krw", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxKrw;
    @Column(name = "net_amount_krw", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmountKrw;
    @Column(name = "quote_at", nullable = false)
    private OffsetDateTime quoteAt;
    @Column(name = "executed_at", nullable = false)
    private OffsetDateTime executedAt;
    /** 소비한 공유 호가 레벨의 고유 ID. 체결 가격은 별도 보존하며, 시세를 직접 쓰는 MARKET은 null입니다. */
    @Column(name = "book_level_id")
    private Long bookLevelId;

    protected TradeExecution() { }

    public static TradeExecution market(TradeOrder order, OrderAmount amount) {
        if (order == null || amount == null || order.getOrderType() != OrderType.MARKET || order.getStatus() != OrderStatus.FILLED
                || order.getExecutedPrice().compareTo(amount.executedPrice()) != 0
                || order.getExchangeRate().compareTo(amount.exchangeRate()) != 0
                || order.getGrossAmount().compareTo(amount.grossAmount()) != 0
                || order.getFee().compareTo(amount.fee()) != 0 || order.getTax().compareTo(amount.tax()) != 0
                || order.getNetAmount().compareTo(amount.netAmount()) != 0) {
            throw new IllegalArgumentException("즉시 체결된 시장가 주문이 필요합니다");
        }
        return create(order, order.getClientOrderId(), 1, order.getQuantity(), amount.executedPrice(),
                ExecutionRateEvidence.rateOnly(amount.exchangeRate()), amount.executionAmounts(),
                order.getQuoteAt(), order.getOrderedAt(), null);
    }

    /** 주문 종목의 시장을 전달합니다. 시장은 검증에만 사용하며 체결에 중복 저장하지 않습니다. */
    public static TradeExecution limit(TradeOrder order, MarketCountry marketCountry, UUID key, int sequenceNo, BigDecimal quantity,
                                       BigDecimal price, ExecutionRateEvidence rate, ExecutionAmounts amounts,
                                       OffsetDateTime quoteAt, OffsetDateTime executedAt, Long bookLevelId) {
        if (order == null || marketCountry == null || amounts == null
                || order.getOrderType() != OrderType.LIMIT || !order.isActive() || rate == null
                || !rate.isValidAt(executedAt) || bookLevelId == null || bookLevelId <= 0
                || executedAt.isBefore(order.getOrderedAt()) || !executedAt.isBefore(order.getExpiresAt())
                || quantity == null || quantity.compareTo(order.activeRemainingQuantity()) > 0
                || sequenceNo != order.getExecutionCount() + 1 || price == null
                || (order.getSide() == OrderSide.BUY ? price.compareTo(order.getLimitPrice()) > 0
                                                   : price.compareTo(order.getLimitPrice()) < 0)) {
            throw new IllegalArgumentException("지정가 체결 조건/시각/호가 근거가 올바르지 않습니다");
        }
        boolean matchesMarket = switch (marketCountry) {
            case KR -> rate.rate().compareTo(BigDecimal.ONE) == 0
                    && amounts.grossAmountUsd().signum() == 0 && amounts.secFeeUsd().signum() == 0;
            case US -> isRepresentableAtScale(price, 2)
                    && amounts.grossAmountUsd().compareTo(price.multiply(quantity)) == 0;
        };
        if (!matchesMarket) {
            throw new IllegalArgumentException("종목 시장과 체결 환율·USD 거래대금·SEC 비용이 일치하지 않습니다");
        }
        return create(order, key, sequenceNo, quantity, price, rate, amounts, quoteAt, executedAt, bookLevelId);
    }

    private static TradeExecution create(TradeOrder order, UUID key, int sequenceNo, BigDecimal quantity,
                                         BigDecimal price, ExecutionRateEvidence rate, ExecutionAmounts amounts,
                                         OffsetDateTime quoteAt, OffsetDateTime executedAt, Long bookLevelId) {
        if (order.getOrderId() == null || key == null || sequenceNo < 1 || quantity == null || quantity.signum() <= 0
                || price == null || price.signum() <= 0 || quoteAt == null || executedAt == null || quoteAt.isAfter(executedAt)
                || amounts == null || !isRepresentableAtScale(quantity, 6)
                || !isRepresentableAtScale(price, 4) || !isRepresentableAtScale(rate.rate(), 6)) {
            throw new IllegalArgumentException("체결 필수 값이 올바르지 않습니다");
        }
        amounts.validateSide(order.getSide());
        BigDecimal nativeGross = price.multiply(quantity);
        if (amounts.unroundedGrossAmountKrw().compareTo(nativeGross.multiply(rate.rate())) != 0
                || (amounts.grossAmountUsd().signum() != 0 && amounts.grossAmountUsd().compareTo(nativeGross) != 0)) {
            throw new IllegalArgumentException("계산에 사용한 거래대금과 저장할 체결 단가·수량·환율이 일치하지 않습니다");
        }
        TradeExecution execution = new TradeExecution();
        execution.orderId = order.getOrderId();
        execution.executionKey = key;
        execution.sequenceNo = sequenceNo;
        execution.quantity = quantity;
        execution.price = price;
        execution.exchangeRate = rate.rate();
        execution.secFeeUsd = amounts.secFeeUsd();
        execution.grossAmountKrw = amounts.grossAmountKrw();
        execution.feeKrw = amounts.feeKrw();
        execution.taxKrw = amounts.taxKrw();
        execution.netAmountKrw = amounts.netAmountKrw();
        execution.quoteAt = quoteAt;
        execution.executedAt = executedAt;
        execution.bookLevelId = bookLevelId;
        return execution;
    }

    /** 계좌·종목·방향은 주문이 소유합니다. 체결을 반영하거나 원장에 기록하기 전에 주문 연결을 검증합니다. */
    public void validateOrder(TradeOrder order) {
        if (order == null || order.getOrderId() == null || !order.getOrderId().equals(orderId)) {
            throw new IllegalArgumentException("체결과 주문이 일치하지 않습니다");
        }
        BigDecimal expected = order.getSide() == OrderSide.BUY ? grossAmountKrw.add(feeKrw)
                : grossAmountKrw.subtract(feeKrw).subtract(taxKrw);
        if (order.getSide() == null || expected.compareTo(netAmountKrw) != 0
                || (order.getSide() == OrderSide.BUY && (taxKrw.signum() != 0 || secFeeUsd.signum() != 0))) {
            throw new IllegalArgumentException("체결 방향과 정산 금액이 일치하지 않습니다");
        }
    }

    /** 주문의 종목 시장을 전달합니다. 환율 값으로 통화를 추측하지 않습니다. */
    public BigDecimal grossAmountUsd(MarketCountry marketCountry) {
        if (marketCountry == null) throw new IllegalArgumentException("종목 시장이 필요합니다");
        return switch (marketCountry) {
            case KR -> BigDecimal.ZERO;
            case US -> price.multiply(quantity);
        };
    }

    /** 저장된 체결 단가·수량·환율로 복원하며 반올림하지 않습니다. 국내 체결 환율은 1입니다. */
    public BigDecimal unroundedGrossAmountKrw() {
        return price.multiply(quantity).multiply(exchangeRate);
    }
    public Long getExecutionId() { return executionId; }
    public Long getOrderId() { return orderId; }
    public UUID getExecutionKey() { return executionKey; }
    public int getSequenceNo() { return sequenceNo; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public BigDecimal getSecFeeUsd() { return secFeeUsd; }
    public BigDecimal getGrossAmountKrw() { return grossAmountKrw; }
    public BigDecimal getFeeKrw() { return feeKrw; }
    public BigDecimal getTaxKrw() { return taxKrw; }
    public BigDecimal getNetAmountKrw() { return netAmountKrw; }
    public OffsetDateTime getQuoteAt() { return quoteAt; }
    public OffsetDateTime getExecutedAt() { return executedAt; }
    public Long getBookLevelId() { return bookLevelId; }
}
