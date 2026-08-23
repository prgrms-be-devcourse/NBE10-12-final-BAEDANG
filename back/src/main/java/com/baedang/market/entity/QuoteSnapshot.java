package com.baedang.market.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * 현재가 스냅샷. <b>종목당 1행이고 UPDATE 합니다</b> — 이력을 쌓지 않습니다.
 *
 * <p>화면이 조회하는 유일한 시세 테이블입니다. 전 종목이 들어 있어서
 * 프론트는 "이 종목이 상위 100인가"를 몰라도 됩니다 —
 * {@code quoteAt} 이 정규장 시간 안이면 실시간, 아니면 전일 종가입니다.
 *
 * <p>장이 닫히면 수집기가 멈추고 {@code lastPrice} 에 종가가 그대로 남습니다.
 * <b>"장외에는 전일 종가를 보여준다" 가 별도 로직 없이 자동으로 됩니다.</b>
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

    /**
     * 전일 종가. 등락률의 분모입니다.
     *
     * <p>토스 현재가 응답에는 등락률이 없어서 직접 계산해야 합니다.
     * 전일 {@code daily_candle.close_price} 를 <b>다음 장 시작 직전</b>에 복사합니다 —
     * 마감 직후에 복사하면 장외 내내 등락률이 0% 로 표시됩니다.
     */
    @Column(name = "prev_close", precision = 19, scale = 4)
    private BigDecimal prevClose;

    @Column(name = "upper_limit", precision = 19, scale = 4)
    private BigDecimal upperLimit;

    @Column(name = "lower_limit", precision = 19, scale = 4)
    private BigDecimal lowerLimit;

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

    public QuoteSnapshot(Long stockId, BigDecimal lastPrice, String currency, OffsetDateTime quoteAt) {
        this.stockId = stockId;
        this.lastPrice = lastPrice;
        this.currency = currency;
        this.quoteAt = quoteAt;
        this.collectedAt = OffsetDateTime.now();
    }

    /** 5초 수집기가 호출합니다. */
    public void updatePrice(BigDecimal lastPrice, OffsetDateTime quoteAt) {
        this.lastPrice = lastPrice;
        this.quoteAt = quoteAt;
        this.collectedAt = OffsetDateTime.now();
    }

    /** 장 시작 직전 배치가 호출합니다. */
    public void updatePrevClose(BigDecimal prevClose) {
        this.prevClose = prevClose;
    }

    /** 상하한가. 국내만 있습니다. */
    public void updateLimits(BigDecimal upperLimit, BigDecimal lowerLimit) {
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
    }

    /** 등락률. prevClose 가 없거나 0 이면 null 을 돌려줍니다 (0% 로 속이지 않습니다). */
    public BigDecimal changeRate() {
        if (prevClose == null || prevClose.signum() == 0) return null;
        return lastPrice.subtract(prevClose).divide(prevClose, 6, RoundingMode.HALF_UP);
    }

    public Long getStockId() { return stockId; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public BigDecimal getPrevClose() { return prevClose; }
    public BigDecimal getUpperLimit() { return upperLimit; }
    public BigDecimal getLowerLimit() { return lowerLimit; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getQuoteAt() { return quoteAt; }
    public OffsetDateTime getCollectedAt() { return collectedAt; }
}
