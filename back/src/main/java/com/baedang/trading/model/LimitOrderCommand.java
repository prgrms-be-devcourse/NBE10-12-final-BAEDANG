package com.baedang.trading.model;

import java.math.BigDecimal;
import java.util.UUID;

public record LimitOrderCommand(
        Long accountId,
        UUID clientOrderId,
        OrderTerms terms,
        BigDecimal requestedPrice,
        String currency
) {}
