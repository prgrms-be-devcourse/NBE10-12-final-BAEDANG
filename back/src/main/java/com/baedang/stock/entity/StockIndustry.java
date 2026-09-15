package com.baedang.stock.entity;

import com.baedang.global.entity.BaseEntity;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "stock_industry")
public class StockIndustry extends BaseEntity {

    @Id
    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "standard_industry_code", length = 10)
    private String standardIndustryCode;

    @Column(name = "standard_industry_name", length = 100)
    private String standardIndustryName;

    @Column(name = "index_industry_large_code", length = 10)
    private String indexIndustryLargeCode;

    @Column(name = "index_industry_large_name", length = 100)
    private String indexIndustryLargeName;

    @Column(name = "index_industry_medium_code", length = 10)
    private String indexIndustryMediumCode;

    @Column(name = "index_industry_medium_name", length = 100)
    private String indexIndustryMediumName;

    @Column(name = "index_industry_small_code", length = 10)
    private String indexIndustrySmallCode;

    @Column(name = "index_industry_small_name", length = 100)
    private String indexIndustrySmallName;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    protected StockIndustry() {
    }

    public static StockIndustry create(Long stockId, IndustryData data, Instant fetchedAt) {
        if (stockId == null) throw new IllegalArgumentException("stockId is required");
        StockIndustry entity = new StockIndustry();
        entity.stockId = stockId;
        entity.update(data, fetchedAt);
        return entity;
    }

    public void update(IndustryData data, Instant fetchedAt) {
        if (data == null || fetchedAt == null) {
            throw new IllegalArgumentException("Industry data and fetchedAt are required");
        }
        this.standardIndustryCode = data.standard() == null ? null : data.standard().code();
        this.standardIndustryName = data.standard() == null ? null : data.standard().name();
        this.indexIndustryLargeCode = data.large() == null ? null : data.large().code();
        this.indexIndustryLargeName = data.large() == null ? null : data.large().name();
        this.indexIndustryMediumCode = data.medium() == null ? null : data.medium().code();
        this.indexIndustryMediumName = data.medium() == null ? null : data.medium().name();
        this.indexIndustrySmallCode = data.small() == null ? null : data.small().code();
        this.indexIndustrySmallName = data.small() == null ? null : data.small().name();
        this.fetchedAt = OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC);
    }

    public Long getStockId() { return stockId; }
    public String getStandardIndustryCode() { return standardIndustryCode; }
    public String getStandardIndustryName() { return standardIndustryName; }
    public String getIndexIndustryLargeCode() { return indexIndustryLargeCode; }
    public String getIndexIndustryLargeName() { return indexIndustryLargeName; }
    public String getIndexIndustryMediumCode() { return indexIndustryMediumCode; }
    public String getIndexIndustryMediumName() { return indexIndustryMediumName; }
    public String getIndexIndustrySmallCode() { return indexIndustrySmallCode; }
    public String getIndexIndustrySmallName() { return indexIndustrySmallName; }
    public OffsetDateTime getFetchedAt() { return fetchedAt; }
}
