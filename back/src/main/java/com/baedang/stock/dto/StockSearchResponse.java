package com.baedang.stock.dto;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockCategory;

import java.util.List;

public record StockSearchResponse(
        List<Item> items
) {
    public record Item(
            String symbol,
            String name,
            String englishName,
            String market,
            MarketCountry marketCountry,
            StockCategory category
    ) {
    }
}
