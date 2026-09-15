package com.baedang.stock.model;

public record CandleQuery(
        CandleQueryInterval interval,
        CandleRange range,
        int count
) {
}
