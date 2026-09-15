package com.baedang.orderbook.dto;

public record OrderBookLevelResponse(
        int level,
        String price,
        String quantity
) {}
