package com.baedang.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * 보유 종목. 원장에서 파생되는 집계이고, 계좌+종목당 한 행입니다.
 *
 * <p>이론적으로는 원장을 재생하면 복원할 수 있지만 조회 성능을 위해 유지합니다.
 *
 * <p><b>락 순서는 항상 {@code account} → {@code holding}</b> 입니다.
 * 엇갈리면 데드락이 납니다.
 */
@Entity
@Table(name = "holding")
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "holding_id")
    private Long holdingId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    /** 매도 주문에 묶인 수량. {@code lockedCash} 와 같은 원리입니다. */
    @Column(name = "locked_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal lockedQuantity;

    /** 평균 매입 단가(평단가). <b>종목 통화 기준</b>이고 매도 시에는 건드리지 않습니다. */
    @Column(name = "avg_buy_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal avgBuyPrice;

    /**
     * 평균 매입 환율. 원화 종목은 1.
     * 지금 안 넣으면 환차손익(주가 손익 vs 환율 손익)을 영원히 분리할 수 없습니다.
     */
    @Column(name = "avg_exchange_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal avgExchangeRate;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Holding() {
    }

    private Holding(Long accountId, Long stockId, BigDecimal quantity,
                    BigDecimal avgBuyPrice, BigDecimal avgExchangeRate,
                    OffsetDateTime updatedAt) {
        this.accountId = accountId;
        this.stockId = stockId;
        this.quantity = quantity;
        this.lockedQuantity = BigDecimal.ZERO;
        this.avgBuyPrice = avgBuyPrice;
        this.avgExchangeRate = avgExchangeRate != null ? avgExchangeRate : BigDecimal.ONE;
        this.updatedAt = updatedAt;
    }

    /** 처음 매수하는 종목일 때. 두 번째부터는 {@link #addBuy} 를 씁니다. */
    public static Holding firstBuy(Long accountId, Long stockId, BigDecimal quantity,
                                   BigDecimal avgBuyPrice, BigDecimal avgExchangeRate) {
        return firstBuy(accountId, stockId, quantity, avgBuyPrice, avgExchangeRate,
                OffsetDateTime.now());
    }

    public static Holding firstBuy(Long accountId, Long stockId, BigDecimal quantity,
                                   BigDecimal avgBuyPrice, BigDecimal avgExchangeRate,
                                   OffsetDateTime updatedAt) {
        return new Holding(accountId, stockId, quantity, avgBuyPrice, avgExchangeRate, updatedAt);
    }

    /** 매도 가능 수량. 저장하지 않고 계산합니다. */
    public BigDecimal availableQuantity() {
        return quantity.subtract(lockedQuantity);
    }

    /**
     * 매수 체결 반영 — 이동평균으로 평단가와 평균환율을 다시 계산합니다.
     *
     * <p>수수료는 평단가에 넣지 않습니다. 종목별 평가손익에는 안 들어가지만
     * 계좌 총 손익에는 이미 반영돼 있습니다 (예수금에서 빠졌으므로).
     */
    public void addBuy(BigDecimal addQty, BigDecimal price, BigDecimal rate) {
        addBuy(addQty, price, rate, OffsetDateTime.now());
    }

    public void addBuy(BigDecimal addQty, BigDecimal price, BigDecimal rate, OffsetDateTime updatedAt) {
        BigDecimal totalQty = quantity.add(addQty);
        this.avgBuyPrice = quantity.multiply(avgBuyPrice)
                .add(addQty.multiply(price))
                .divide(totalQty, 4, RoundingMode.HALF_UP);
        this.avgExchangeRate = quantity.multiply(avgExchangeRate)
                .add(addQty.multiply(rate))
                .divide(totalQty, 6, RoundingMode.HALF_UP);
        this.quantity = totalQty;
        this.updatedAt = updatedAt;
    }

    /** 매도 체결 반영. <b>평단가는 그대로 둡니다</b> — 평가손익의 기준이기 때문입니다. */
    public void subtractSell(BigDecimal sellQty) {
        subtractSell(sellQty, OffsetDateTime.now());
    }

    public void subtractSell(BigDecimal sellQty, OffsetDateTime updatedAt) {
        if (sellQty == null || sellQty.signum() <= 0) {
            throw new IllegalArgumentException("매도 수량은 0보다 커야 합니다");
        }
        if (availableQuantity().compareTo(sellQty) < 0) {
            throw new IllegalStateException("매도 가능 수량보다 많이 차감할 수 없습니다");
        }
        this.quantity = this.quantity.subtract(sellQty);
        this.updatedAt = updatedAt;
    }

    public Long getHoldingId() { return holdingId; }
    public Long getAccountId() { return accountId; }
    public Long getStockId() { return stockId; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getLockedQuantity() { return lockedQuantity; }
    public BigDecimal getAvgBuyPrice() { return avgBuyPrice; }
    public BigDecimal getAvgExchangeRate() { return avgExchangeRate; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
