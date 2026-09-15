package com.baedang.stock.client.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * Toss GET /api/v1/stocks 원본 응답 DTO.
 *
 * 가격·수량성 문자열(sharesOutstanding, leverageFactor)은 그대로 String으로 받고,
 * BigDecimal 변환은 Adapter의 책임이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossStockInfoResponse(
        List<TossStockInfo> result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KrMarketDetail(
            Boolean liquidationTrading,
            Boolean nxtSupported,
            Boolean krxTradingSuspended,
            Boolean nxtTradingSuspended
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TossStockInfo(
            String symbol,
            String name,
            String englishName,
            String isinCode,
            String market,
            String securityType,
            Boolean isCommonShare,
            String status,
            String currency,
            LocalDate listDate,
            LocalDate delistDate,
            String sharesOutstanding,
            String leverageFactor,
            KrMarketDetail koreanMarketDetail
    ) {
    }
}
