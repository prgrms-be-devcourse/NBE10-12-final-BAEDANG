package com.baedang.trading.model;

import java.util.UUID;

/** 멱등성 키와 정규화된 시장가 주문 조건입니다. */
public record MarketOrderCommand(UUID clientOrderId, OrderTerms terms) {
}
