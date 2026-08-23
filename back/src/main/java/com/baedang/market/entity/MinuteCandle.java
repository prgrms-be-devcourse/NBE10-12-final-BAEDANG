package com.baedang.market.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 분봉.
 *
 * <p><b>1주차에는 온디맨드로 채웁니다.</b> 사용자가 종목 상세를 열 때
 * 토스 {@code /candles?interval=1m} 을 호출하고, 받은 봉을 여기 넣은 뒤
 * 60초 동안은 DB 에서 바로 내려줍니다. 테이블이 저장소이자 캐시를 겸합니다.
 *
 * <p>장이 닫힌 종목에 요청해도 <b>마지막 장의 분봉</b>이 그대로 옵니다 —
 * 한국 낮에 엔비디아를 열면 전일 종가 + 지난 미국장 차트가 보입니다.
 *
 * <p>2주차에 스케줄러 상시 적재로 전환하면서 하이퍼테이블로 만듭니다.
 * 그때 {@code high}/{@code low} 가 지정가 체결 판정 근거가 됩니다.
 */
@Entity
@Table(name = "minute_candle")
@IdClass(MinuteCandle.Pk.class)
public class MinuteCandle {

    @Id
    @Column(name = "stock_id")
    private Long stockId;

    /** 봉 <b>시작</b> 시각 (응답의 timestamp). */
    @Id
    @Column(name = "candle_at")
    private OffsetDateTime candleAt;

    @Column(name = "open_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal closePrice;

    @Column(name = "volume", precision = 20, scale = 0)
    private BigDecimal volume;

    protected MinuteCandle() {
    }

    public MinuteCandle(Long stockId, OffsetDateTime candleAt, BigDecimal openPrice,
                        BigDecimal highPrice, BigDecimal lowPrice, BigDecimal closePrice,
                        BigDecimal volume) {
        this.stockId = stockId;
        this.candleAt = candleAt;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.volume = volume;
    }

    public Long getStockId() { return stockId; }
    public OffsetDateTime getCandleAt() { return candleAt; }
    public BigDecimal getOpenPrice() { return openPrice; }
    public BigDecimal getHighPrice() { return highPrice; }
    public BigDecimal getLowPrice() { return lowPrice; }
    public BigDecimal getClosePrice() { return closePrice; }
    public BigDecimal getVolume() { return volume; }

    public static class Pk implements Serializable {
        private Long stockId;
        private OffsetDateTime candleAt;

        public Pk() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(stockId, pk.stockId) && Objects.equals(candleAt, pk.candleAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stockId, candleAt);
        }
    }
}
