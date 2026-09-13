package com.baedang.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "stock_like")
public class StockLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stock_like_id")
    private Long stockLikeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected StockLike() {
    }

    public Long getStockLikeId() {
        return stockLikeId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getStockId() {
        return stockId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
