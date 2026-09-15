package com.baedang.orderbook.model;

import com.baedang.orderbook.entity.OrderBookSide;

import java.math.BigDecimal;
import java.util.Objects;

/** 생성기가 만든 단일 호가 레벨(아직 영속화 전 값). */
public record GeneratedOrderBookLevel(
        OrderBookSide side,
        int levelDepth,
        BigDecimal price,
        BigDecimal quantity
) {
    public GeneratedOrderBookLevel {
        Objects.requireNonNull(side, "side는 필수입니다");
        Objects.requireNonNull(price, "price는 필수입니다");
        Objects.requireNonNull(quantity, "quantity는 필수입니다");
        if (levelDepth < 1 || levelDepth > 10) {
            throw new IllegalArgumentException("levelDepth는 1 이상 10 이하이어야 합니다: " + levelDepth);
        }
    }
}
