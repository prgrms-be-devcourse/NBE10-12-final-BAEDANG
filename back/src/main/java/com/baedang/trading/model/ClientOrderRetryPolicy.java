package com.baedang.trading.model;

import java.util.Map;

/** 주문 실패 후 clientOrderId를 어떻게 다뤄야 하는지 나타내는 API 계약입니다. */
public enum ClientOrderRetryPolicy {
    SAME_CLIENT_ORDER_ID,
    NEW_CLIENT_ORDER_ID,
    NOT_RETRYABLE;

    public Map<String, Object> asData() {
        return Map.of("retryPolicy", name());
    }
}
