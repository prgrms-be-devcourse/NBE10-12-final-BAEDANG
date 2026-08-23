package com.baedang.trading.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 일별 자산 스냅샷.
 *
 * <p><b>1주차에는 화면에 쓰지 않지만 배치는 지금 넣으세요.</b> 매일 장 마감 후
 * 한 줄씩 쌓는 단순한 작업인데, 이게 없으면 2주차에 자산 추이 그래프를 그릴
 * <b>과거 데이터가 아예 없습니다.</b> 컬럼은 나중에 추가할 수 있지만
 * 지나간 날짜의 자산은 복원할 수 없습니다.
 */
@Entity
@Table(name = "daily_account_snapshot")
@IdClass(DailyAccountSnapshot.Pk.class)
public class DailyAccountSnapshot {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Id
    @Column(name = "snapshot_date")
    private LocalDate snapshotDate;

    @Column(name = "cash_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal cashBalance;

    /** 보유 종목 평가금액 합계 (원화 환산). */
    @Column(name = "stock_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal stockValue;

    @Column(name = "total_asset", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAsset;

    @Column(name = "unrealized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal unrealizedPnl;

    protected DailyAccountSnapshot() {
    }

    public DailyAccountSnapshot(Long accountId, LocalDate snapshotDate, BigDecimal cashBalance,
                                BigDecimal stockValue, BigDecimal unrealizedPnl) {
        this.accountId = accountId;
        this.snapshotDate = snapshotDate;
        this.cashBalance = cashBalance;
        this.stockValue = stockValue;
        this.totalAsset = cashBalance.add(stockValue);
        this.unrealizedPnl = unrealizedPnl;
    }

    public Long getAccountId() { return accountId; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public BigDecimal getCashBalance() { return cashBalance; }
    public BigDecimal getStockValue() { return stockValue; }
    public BigDecimal getTotalAsset() { return totalAsset; }
    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }

    public static class Pk implements Serializable {
        private Long accountId;
        private LocalDate snapshotDate;

        public Pk() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(accountId, pk.accountId)
                    && Objects.equals(snapshotDate, pk.snapshotDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, snapshotDate);
        }
    }
}
