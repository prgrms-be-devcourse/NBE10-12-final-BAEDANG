package com.baedang.orderbook.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public interface OrderBookRowProjection {
    LocalDate getPriceLimitDate();
    BigDecimal getLowerLimit();
    BigDecimal getUpperLimit();
    String getLimitCurrency();
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
