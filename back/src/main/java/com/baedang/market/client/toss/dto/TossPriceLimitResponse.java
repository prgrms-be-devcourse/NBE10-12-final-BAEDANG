package com.baedang.market.client.toss.dto;

import java.time.OffsetDateTime;

public record TossPriceLimitResponse(Result result) {
    public record Result(OffsetDateTime timestamp, String upperLimitPrice,
                         String lowerLimitPrice, String currency) {}
}
