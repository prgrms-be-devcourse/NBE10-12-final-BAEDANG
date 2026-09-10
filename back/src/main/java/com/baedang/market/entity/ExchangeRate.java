package com.baedang.market.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 환율. <b>일반 테이블입니다</b> — 하이퍼테이블로 만들지 않습니다.
 *
 * <p>1분마다 수집하며 같은 통화쌍·validFrom 응답은 기존 행을 갱신합니다.
 * 화면과 체결이 같은 저장소를 읽되 체결은 원본 유효기간을 반드시 검증합니다.
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
    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    /** 유효 종료 시각(미포함). */
    @Column(name = "valid_until", nullable = false)
    private OffsetDateTime validUntil;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt;

    protected ExchangeRate() {
    }

    public ExchangeRate(String baseCurrency, String quoteCurrency, BigDecimal rate,
                        BigDecimal midRate, OffsetDateTime validFrom, OffsetDateTime validUntil, OffsetDateTime collectedAt) {
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rate = rate;
        this.midRate = midRate;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.collectedAt = collectedAt;
    }

    public Long getExchangeRateId() { return exchangeRateId; }
    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getMidRate() { return midRate; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public OffsetDateTime getValidUntil() { return validUntil; }
    public OffsetDateTime getCollectedAt() { return collectedAt; }
}
