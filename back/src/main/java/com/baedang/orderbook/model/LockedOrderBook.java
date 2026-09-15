package com.baedang.orderbook.model;

import com.baedang.orderbook.entity.OrderBookLevel;
import com.baedang.orderbook.entity.OrderBookVersion;

import java.util.List;

public record LockedOrderBook(
        OrderBookVersion version,
        List<OrderBookLevel> levels
) {
    public LockedOrderBook {
        levels = List.copyOf(levels);
    }
}
