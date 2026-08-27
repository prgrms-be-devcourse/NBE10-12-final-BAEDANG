package com.baedang.trading.dto;

/** 금액·수량 정밀도를 잃지 않도록 수량을 문자열로 받습니다. */
public record PlaceOrderRequest(
        String clientOrderId,
        String symbol,
        String marketCountry,
        String side,
        String quantity
) {
}
