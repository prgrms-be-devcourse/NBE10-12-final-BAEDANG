package com.baedang.trading.dto;

import com.baedang.stock.entity.MarketCountry;

import java.util.List;

/** 종목 정보는 페이지 상위에서 한 번 제공하며, 체결이 없어도 유지합니다. */
public record OrderExecutionsResponse(
        Long orderId,
        StockSummary stock,
        List<ExecutionResponse> items,
        String nextCursor,
        boolean hasNext
) {
    public record StockSummary(String symbol, String name, MarketCountry marketCountry) {}
}
