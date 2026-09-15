package com.baedang.trading.model;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;

import java.math.BigDecimal;

/** 시장가 견적과 주문이 공유하는 정규화된 주문 조건입니다. */
public record OrderTerms(
        String symbol,
        MarketCountry marketCountry,
        OrderSide side,
        BigDecimal quantity
) {
}
