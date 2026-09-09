package com.baedang.orderbook.repository;

import java.math.BigDecimal;
import java.time.Instant;

public interface OrderBookRowProjection {
    Long getLevelId();
    Long getBookVersion();
    Long getRevision();
    BigDecimal getBasePrice();
    String getCurrency();
    Instant getQuoteAt();
    Instant getGeneratedAt();
    String getPolicyVersion();
    Long getSeed();
    String getSide();
    Integer getLevelDepth();
    BigDecimal getPrice();
    BigDecimal getRemainingQuantity();
}
