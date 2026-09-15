package com.baedang.trading.dto;

public record LimitOrderRequest(
        Long accountId,
        String clientOrderId,
        String symbol,
        String marketCountry,
        String side,
        String quantity,
        String limitPrice,
        String limitCurrency
) {
}
