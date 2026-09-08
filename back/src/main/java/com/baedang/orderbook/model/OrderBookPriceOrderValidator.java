package com.baedang.orderbook.model;

import com.baedang.orderbook.entity.OrderBookSide;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

public final class OrderBookPriceOrderValidator {

    private OrderBookPriceOrderValidator() {
    }

    public static <T> boolean isStrict(
            List<T> levels,
            Function<T, BigDecimal> priceExtractor,
            OrderBookSide side
    ) {
        for (int i = 1; i < levels.size(); i++) {
            int comparison = priceExtractor.apply(levels.get(i - 1))
                    .compareTo(priceExtractor.apply(levels.get(i)));
            if (side == OrderBookSide.ASK ? comparison >= 0 : comparison <= 0) {
                return false;
            }
        }
        return true;
    }
}
