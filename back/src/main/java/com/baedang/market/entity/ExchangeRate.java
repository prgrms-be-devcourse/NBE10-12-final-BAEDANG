package com.baedang.market.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 환율. <b>일반 테이블입니다</b> — 하이퍼테이블로 만들지 않습니다.
 *
 * <p>매시 정각 적재라 통화쌍당 연 6,000행이고, 10년을 모아도 6만 행입니다.
 * 일간·주간 그래프는 그냥 GROUP BY 하면 되므로 연속 집계도 필요 없습니다.
 *
 * <p>다른 테이블과 FK 로 연결하지 않습니다 — <b>원장에 필요한 환율은
 * "그때 그 값"이지 참조가 아니어야</b> 하기 때문입니다. 나중에 환율 데이터를
 * 정정해도 과거 체결 기록은 흔들리면 안 됩니다.
 */
@Entity
@Table(name = "exchange_rate")
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exchange_rate_id")
    private Long exchangeRateId;

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    @Column(name = "quote_currency", nullable = false, length = 3)
    private String quoteCurrency;

    /** 실제 매수 시 적용되는 환율. mid_rate 와의 차이가 환전 스프레드입니다. */
    @Column(name = "rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal rate;

    /** 은행간 매매기준율. 일반적인 "환율"로 표시할 때 씁니다. */
    @Column(name = "mid_rate", precision = 19, scale = 6)
    private BigDecimal midRate;

    /** 응답의 validFrom. 우리가 받은 시각(collectedAt)과 구분하세요. */
    @Column(name = "rate_at", nullable = false)
    private OffsetDateTime rateAt;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt;

    protected ExchangeRate() {
    }

    public ExchangeRate(String baseCurrency, String quoteCurrency, BigDecimal rate,
                        BigDecimal midRate, OffsetDateTime rateAt) {
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rate = rate;
        this.midRate = midRate;
        this.rateAt = rateAt;
        this.collectedAt = OffsetDateTime.now();
    }

    public Long getExchangeRateId() { return exchangeRateId; }
    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getMidRate() { return midRate; }
    public OffsetDateTime getRateAt() { return rateAt; }
    public OffsetDateTime getCollectedAt() { return collectedAt; }
}
