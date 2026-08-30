package com.baedang.market.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 일봉. <b>TimescaleDB 하이퍼테이블</b>입니다 (infra/timescale.sql).
 *
 * <p>용도가 둘입니다 — 일봉 차트, 그리고 {@code prev_close} 의 원천.
 * 장 마감 10분 후 수집합니다(국내 15:40 KST, 미국 16:10 ET).
 *
 * <p>하이퍼테이블이라도 JPA 에서는 평범한 테이블처럼 다루면 됩니다.
 * 주봉은 연속 집계 뷰({@code candle_1w})로 파생하므로 여기서 만들지 마세요.
 *
 * <p>{@code tradeDate} 는 <b>KST 기준 날짜</b>입니다. 토스 응답의
 * {@code timestamp} 는 시각이므로 UTC 날짜를 그대로 사용하지 않습니다.
 */
@Entity
@Table(name = "daily_candle")
@IdClass(DailyCandle.Pk.class)
public class DailyCandle {

    @Id
    @Column(name = "stock_id")
    private Long stockId;

    @Id
    @Column(name = "trade_date")
    private LocalDate tradeDate;

    @Column(name = "open_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal lowPrice;

    /** 종가. 다음 거래일의 {@code prev_close} 로 복사됩니다. */
    @Column(name = "close_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal closePrice;

    @Column(name = "volume", precision = 20, scale = 0)
    private BigDecimal volume;

    protected DailyCandle() {
    }

    public DailyCandle(Long stockId, LocalDate tradeDate, BigDecimal openPrice,
                       BigDecimal highPrice, BigDecimal lowPrice, BigDecimal closePrice,
                       BigDecimal volume) {
        this.stockId = stockId;
        this.tradeDate = tradeDate;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.volume = volume;
    }

    public Long getStockId() { return stockId; }
    public LocalDate getTradeDate() { return tradeDate; }
    public BigDecimal getOpenPrice() { return openPrice; }
    public BigDecimal getHighPrice() { return highPrice; }
    public BigDecimal getLowPrice() { return lowPrice; }
    public BigDecimal getClosePrice() { return closePrice; }
    public BigDecimal getVolume() { return volume; }

    public static class Pk implements Serializable {
        private Long stockId;
        private LocalDate tradeDate;

        public Pk() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(stockId, pk.stockId) && Objects.equals(tradeDate, pk.tradeDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stockId, tradeDate);
        }
    }
}
