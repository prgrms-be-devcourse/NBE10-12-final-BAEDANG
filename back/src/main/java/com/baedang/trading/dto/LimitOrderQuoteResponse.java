package com.baedang.trading.dto;

import com.baedang.global.error.ErrorCode;

import java.time.OffsetDateTime;
import java.util.Map;

public record LimitOrderQuoteResponse(
        String requestedLimitPrice,
        String requestedLimitCurrency,
        String limitPrice,
        String acceptanceExchangeRate,
        boolean acceptable,
        ErrorCode reason,
        String availableCash,
        String availableQuantity,
        OffsetDateTime expiresAt,
        Estimate limitEstimate,
        Map<String, String> executionPreview
) {

    public record Estimate(
            String grossAmount,
            String fee,
            String tax,
            String netAmount,
            String reservedCash
    ) {}
}
