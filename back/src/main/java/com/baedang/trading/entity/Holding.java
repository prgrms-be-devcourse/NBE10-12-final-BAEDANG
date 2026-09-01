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

    private static final int AVG_BUY_PRICE_SCALE = 4;
    private static final int AVG_EXCHANGE_RATE_SCALE = 6;
    private static final int USD_PURCHASE_AMOUNT_SCALE = 10;
    private static final int KRW_PURCHASE_AMOUNT_SCALE = 16;

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

    /** 현재 잔여 수량에 귀속되는 수수료 제외 USD 매수금액. 국내 종목은 0입니다. */
    @Column(name = "usd_purchase_amount", nullable = false, precision = 29, scale = 10)
    private BigDecimal usdPurchaseAmount;

    /** 현재 잔여 수량에 귀속되는 원 단위 반올림 전·수수료 제외 원화 매수금액입니다. */
    @Column(name = "krw_purchase_amount", nullable = false, precision = 38, scale = 16)
    private BigDecimal krwPurchaseAmount;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Holding() {
    }

    private Holding(Long accountId, Long stockId, BigDecimal quantity,
                    BigDecimal usdPurchaseAmount, BigDecimal krwPurchaseAmount,
                    OffsetDateTime updatedAt) {
        requirePositive(quantity, "매수 수량");
        requireNonNegative(usdPurchaseAmount, "USD 매수금액");
        requirePositive(krwPurchaseAmount, "원화 매수금액");
        this.accountId = accountId;
        this.stockId = stockId;
        this.quantity = quantity;
        this.lockedQuantity = BigDecimal.ZERO;
        this.usdPurchaseAmount = usdPurchaseAmount;
        this.krwPurchaseAmount = krwPurchaseAmount;
        recalculateAverages();
        this.updatedAt = updatedAt;
    }

    /** 처음 매수하는 종목일 때. 두 번째부터는 {@link #addBuy} 를 씁니다. */
    public static Holding firstBuy(Long accountId, Long stockId, BigDecimal quantity,
                                   BigDecimal usdPurchaseAmount, BigDecimal krwPurchaseAmount,
                                   OffsetDateTime updatedAt) {
        return new Holding(
                accountId, stockId, quantity, usdPurchaseAmount, krwPurchaseAmount, updatedAt);
    }

    /** 매도 가능 수량. 저장하지 않고 계산합니다. */
    public BigDecimal availableQuantity() {
        return quantity.subtract(lockedQuantity);
    }

    /**
     * 매수 체결 반영 — 이동평균으로 평단가와 평균환율을 다시 계산합니다.
     *
     * <p>반올림된 평균값을 다음 매수 계산에 재사용하지 않습니다. 체결마다 USD·원화
     * 반올림 전 매수금액을 합산한 뒤 그 합계에서 평단가와 평균환율을 다시 계산합니다.
     *
     * <p>수수료는 평단가에 넣지 않습니다. 종목별 평가손익에는 안 들어가지만
     * 계좌 총 손익에는 이미 반영돼 있습니다 (예수금에서 빠졌으므로).
     */
    public void addBuy(BigDecimal addQty, BigDecimal addedUsdPurchaseAmount,
                       BigDecimal addedKrwPurchaseAmount,
                       OffsetDateTime updatedAt) {
        requirePositive(addQty, "매수 수량");
        requireNonNegative(addedUsdPurchaseAmount, "USD 매수금액");
        requirePositive(addedKrwPurchaseAmount, "원화 매수금액");
        requireSamePurchaseCurrency(addedUsdPurchaseAmount);

        this.quantity = quantity.add(addQty);
        this.usdPurchaseAmount = usdPurchaseAmount.add(addedUsdPurchaseAmount);
        this.krwPurchaseAmount = krwPurchaseAmount.add(addedKrwPurchaseAmount);
        recalculateAverages();
        this.updatedAt = updatedAt;
    }

    private void requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + "은 0보다 커야 합니다");
        }
    }

    private void requireNonNegative(BigDecimal value, String fieldName) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + "은 0 이상이어야 합니다");
        }
    }

    private void requireSamePurchaseCurrency(BigDecimal addedUsdPurchaseAmount) {
        if (quantity.signum() == 0) {
            return;
        }
        boolean existingIsUsd = usdPurchaseAmount.signum() > 0;
        boolean addedIsUsd = addedUsdPurchaseAmount.signum() > 0;
        if (existingIsUsd != addedIsUsd) {
            throw new IllegalArgumentException("기존 보유 종목과 같은 통화의 매수금액이어야 합니다");
        }
    }

    private void recalculateAverages() {
        if (usdPurchaseAmount.signum() > 0) {
            this.avgBuyPrice = usdPurchaseAmount.divide(
                    quantity, AVG_BUY_PRICE_SCALE, RoundingMode.HALF_UP);
            this.avgExchangeRate = krwPurchaseAmount.divide(
                    usdPurchaseAmount, AVG_EXCHANGE_RATE_SCALE, RoundingMode.HALF_UP);
            return;
        }
        this.avgBuyPrice = krwPurchaseAmount.divide(
                quantity, AVG_BUY_PRICE_SCALE, RoundingMode.HALF_UP);
        this.avgExchangeRate = BigDecimal.ONE;
    }

    /**
     * 매도 체결 반영. 부분 매도는 잔여 수량 비율만큼 매수금액을 남기고 평단가는 유지합니다.
     * 전량 매도는 다음 재매수가 과거 원가를 승계하지 않도록 모든 매수금액을 0으로 초기화합니다.
     */
    public void subtractSell(BigDecimal sellQty, OffsetDateTime updatedAt) {
        if (sellQty == null || sellQty.signum() <= 0) {
            throw new IllegalArgumentException("매도 수량은 0보다 커야 합니다");
        }
        if (availableQuantity().compareTo(sellQty) < 0) {
            throw new IllegalStateException("매도 가능 수량보다 많이 차감할 수 없습니다");
        }
        BigDecimal previousQuantity = this.quantity;
        BigDecimal remainingQuantity = previousQuantity.subtract(sellQty);
        if (remainingQuantity.signum() == 0) {
            this.quantity = BigDecimal.ZERO;
            this.usdPurchaseAmount = BigDecimal.ZERO;
            this.krwPurchaseAmount = BigDecimal.ZERO;
        } else {
            this.usdPurchaseAmount = proportionalRemainingAmount(
                    usdPurchaseAmount, remainingQuantity, previousQuantity, USD_PURCHASE_AMOUNT_SCALE);
            this.krwPurchaseAmount = proportionalRemainingAmount(
                    krwPurchaseAmount, remainingQuantity, previousQuantity, KRW_PURCHASE_AMOUNT_SCALE);
            this.quantity = remainingQuantity;
        }
        this.updatedAt = updatedAt;
    }

    private BigDecimal proportionalRemainingAmount(
            BigDecimal amount, BigDecimal remainingQuantity, BigDecimal previousQuantity, int scale
    ) {
        if (amount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(remainingQuantity)
                .divide(previousQuantity, scale, RoundingMode.HALF_UP);
    }

    public Long getHoldingId() { return holdingId; }
    public Long getAccountId() { return accountId; }
    public Long getStockId() { return stockId; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getLockedQuantity() { return lockedQuantity; }
    public BigDecimal getAvgBuyPrice() { return avgBuyPrice; }
    public BigDecimal getAvgExchangeRate() { return avgExchangeRate; }
    public BigDecimal getUsdPurchaseAmount() { return usdPurchaseAmount; }
    public BigDecimal getKrwPurchaseAmount() { return krwPurchaseAmount; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
