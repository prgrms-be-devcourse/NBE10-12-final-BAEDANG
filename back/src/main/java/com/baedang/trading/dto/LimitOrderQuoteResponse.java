package com.baedang.trading.dto;

import com.baedang.global.error.ErrorCode;

import java.time.OffsetDateTime;

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
        LimitExecutionPreviewResponse executionPreview
) {

    public record Estimate(
            String grossAmount,
            String fee,
            String tax,
            String netAmount,
            String reservedCash
    ) {}
}
