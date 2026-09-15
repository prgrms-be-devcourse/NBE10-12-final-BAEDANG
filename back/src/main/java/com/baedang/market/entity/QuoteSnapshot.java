package com.baedang.market.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 종목당 최신 정규장 시세. 직전 거래일의 확정 종가와 그 날짜를 저장한다. 시세 거래일은 quoteAt에서 계산한다.
 * 마감 후 복구한 일봉 종가는 캘린더의 정규장 종료 시각을 quoteAt으로 사용한다.
 * 기준가 검증에 실패하면 가격은 제공하되 등락률은 null이다.
 */
@Entity
@Table(name = "quote_snapshot")
public class QuoteSnapshot {

    /** {@code stock_id} 가 그대로 PK 입니다 (종목당 1행). */
    @Id
    @Column(name = "stock_id")
    private Long stockId;

    @Column(name = "last_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal lastPrice;

    /** 시세 거래일의 직전 거래일에 확정된 일봉 종가. */
    @Column(name = "prev_close", precision = 19, scale = 4)
    private BigDecimal prevClose;

    @Column(name = "prev_close_date")
    private LocalDate prevCloseDate;

    public void applyReference(LocalDate prevCloseDate, BigDecimal prevClose) {
        this.prevCloseDate = prevCloseDate;
        this.prevClose = prevClose;
    }

    public LocalDate getPrevCloseDate() { return prevCloseDate; }

    @Column(name = "upper_limit", precision = 19, scale = 4)
    private BigDecimal upperLimit;

    @Column(name = "lower_limit", precision = 19, scale = 4)
    private BigDecimal lowerLimit;

    @Column(name = "price_limit_date")
    private LocalDate priceLimitDate;

    public LocalDate getPriceLimitDate() { return priceLimitDate; }

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * 시세의 기준 시각(거래소 기준). <b>수집 시각과 다릅니다.</b>
     * 주문 체결 시 이 값이 15초보다 오래됐으면 STALE_QUOTE 로 거절합니다.
     */
    @Column(name = "quote_at", nullable = false)
    private OffsetDateTime quoteAt;

    /** 우리가 받은 시각. 수집기 지연을 진단할 때 씁니다. */
    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt;

    protected QuoteSnapshot() {
    }

    public QuoteSnapshot(
            Long stockId,
            BigDecimal lastPrice,
            String currency,
            OffsetDateTime quoteAt,
            OffsetDateTime collectedAt
    ) {
        this.stockId = stockId;
        this.lastPrice = lastPrice;
        this.currency = currency;
        this.quoteAt = quoteAt;
        this.collectedAt = collectedAt;
    }

    /** 5초 수집기가 호출합니다. */
    public void updatePrice(
            BigDecimal lastPrice,
            String currency,
            OffsetDateTime quoteAt,
            OffsetDateTime collectedAt
    ) {
        this.lastPrice = lastPrice;
        this.currency = currency;
        this.quoteAt = quoteAt;
        this.collectedAt = collectedAt;
    }

    /** 상하한가. 국내만 있습니다. */
    public void updateLimits(BigDecimal upperLimit, BigDecimal lowerLimit) {
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
    }

    /** 검증된 기준가가 없거나 양수가 아니면 등락률은 null을 반환한다. */
    public BigDecimal changeRate() {
        BigDecimal referenceClose = getPrevClose();
        if (referenceClose == null || referenceClose.signum() <= 0) {
            return null;
        }
        return lastPrice.subtract(referenceClose).divide(referenceClose, 6, RoundingMode.HALF_UP);
    }

    public Long getStockId() { return stockId; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public BigDecimal getPrevClose() {
        return prevCloseDate != null ? prevClose : null;
    }
    public BigDecimal getUpperLimit() { return upperLimit; }
    public BigDecimal getLowerLimit() { return lowerLimit; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getQuoteAt() { return quoteAt; }
    public OffsetDateTime getCollectedAt() { return collectedAt; }
}
