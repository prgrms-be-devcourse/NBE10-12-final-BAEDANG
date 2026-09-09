package com.baedang.orderbook.entity;

import com.baedang.orderbook.model.GeneratedOrderBook;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Entity
@Table(name = "order_book_version")
public class OrderBookVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "book_version_id")
    private Long bookVersionId;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "base_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal basePrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "quote_at", nullable = false)
    private OffsetDateTime quoteAt;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(name = "seed", nullable = false)
    private Long seed;

    @Column(name = "revision", nullable = false)
    private Long revision;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    protected OrderBookVersion() {
    }

    private OrderBookVersion(
            Long stockId,
            BigDecimal basePrice,
            String currency,
            Instant quoteAt,
            Instant generatedAt,
            String policyVersion,
            long seed
    ) {
        this.stockId = Objects.requireNonNull(stockId, "stockId는 필수입니다");
        this.basePrice = Objects.requireNonNull(basePrice, "basePrice는 필수입니다");
        if (basePrice.signum() <= 0) {
            throw new IllegalArgumentException("basePrice는 양수여야 합니다");
        }
        this.currency = Objects.requireNonNull(currency, "currency는 필수입니다");
        this.quoteAt = Objects.requireNonNull(quoteAt, "quoteAt은 필수입니다").atOffset(ZoneOffset.UTC);
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt은 필수입니다").atOffset(ZoneOffset.UTC);
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion은 필수입니다");
        this.seed = seed;
        this.revision = 0L;
        this.isActive = true;
        this.closedAt = null;
    }

    public static OrderBookVersion open(
            Long stockId,
            BigDecimal basePrice,
            String currency,
            Instant quoteAt,
            Instant generatedAt,
            String policyVersion,
            long seed
    ) {
        return new OrderBookVersion(
                stockId,
                basePrice,
                currency,
                quoteAt,
                generatedAt,
                policyVersion,
                seed
        );
    }

    /** publisher가 생성 입력({@link GeneratedOrderBook}) 그대로 새 활성 버전을 연다. */
    public static OrderBookVersion open(GeneratedOrderBook generated) {
        return new OrderBookVersion(
                generated.stockId(),
                generated.basePrice(),
                generated.currency(),
                generated.quoteAt(),
                generated.generatedAt(),
                generated.policyVersion(),
                generated.seed()
        );
    }

    public void close(Instant closedAt) {
        if (!Boolean.TRUE.equals(this.isActive)) {
            throw new IllegalStateException("이미 종료된 호가 버전입니다");
        }
        this.closedAt = Objects.requireNonNull(closedAt, "closedAt은 필수입니다").atOffset(ZoneOffset.UTC);
        this.isActive = false;
    }

    public void advanceRevision() {
        if (!Boolean.TRUE.equals(this.isActive)) {
            throw new IllegalStateException("종료된 호가 버전의 revision은 변경할 수 없습니다");
        }
        this.revision = Math.addExact(this.revision, 1L);
    }

    public Long getBookVersionId() {
        return bookVersionId;
    }

    public Long getStockId() {
        return stockId;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public String getCurrency() {
        return currency;
    }

    public OffsetDateTime getQuoteAt() {
        return quoteAt;
    }

    public OffsetDateTime getGeneratedAt() {
        return generatedAt;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public Long getSeed() {
        return seed;
    }

    public Long getRevision() {
        return revision;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(isActive);
    }

    public OffsetDateTime getClosedAt() {
        return closedAt;
    }
}
