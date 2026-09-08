package com.baedang.stock.entity;

import com.baedang.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "stock_financial_sync")
public class StockFinancialSync extends BaseEntity {

    @Id
    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "industry_synced_at")
    private OffsetDateTime industrySyncedAt;

    @Column(name = "annual_synced_at")
    private OffsetDateTime annualSyncedAt;

    @Column(name = "quarterly_synced_at")
    private OffsetDateTime quarterlySyncedAt;

    protected StockFinancialSync() {
    }

    public static StockFinancialSync create(Long stockId) {
        if (stockId == null) throw new IllegalArgumentException("stockId is required");
        StockFinancialSync sync = new StockFinancialSync();
        sync.stockId = stockId;
        return sync;
    }

    public void markIndustrySynced(Instant syncedAt) {
        if (syncedAt == null) throw new IllegalArgumentException("syncedAt is required");
        this.industrySyncedAt = utc(syncedAt);
    }

    public void markFinancialSynced(FinancialPeriodType periodType, Instant syncedAt) {
        if (periodType == null || syncedAt == null) {
            throw new IllegalArgumentException("periodType and syncedAt are required");
        }
        if (periodType == FinancialPeriodType.ANNUAL) {
            this.annualSyncedAt = utc(syncedAt);
        } else if (periodType == FinancialPeriodType.QUARTERLY) {
            this.quarterlySyncedAt = utc(syncedAt);
        } else {
            throw new IllegalArgumentException("Unsupported financial period type: " + periodType);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public Long getStockId() { return stockId; }
    public OffsetDateTime getIndustrySyncedAt() { return industrySyncedAt; }
    public OffsetDateTime getAnnualSyncedAt() { return annualSyncedAt; }
    public OffsetDateTime getQuarterlySyncedAt() { return quarterlySyncedAt; }
}
