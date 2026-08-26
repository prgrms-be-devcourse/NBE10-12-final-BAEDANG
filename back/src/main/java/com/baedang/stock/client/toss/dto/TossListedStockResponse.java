package com.baedang.stock.client.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Toss GET /api/v1/stocks/all 원본 응답 DTO.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossListedStockResponse(
        List<TossListedStockItem> result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TossListedStockItem(
            String symbol,
            String name,
            String securityType,
            Boolean isCommonShare,
            String isinCode
    ) {

    }
}
