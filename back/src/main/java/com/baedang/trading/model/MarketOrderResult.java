package com.baedang.trading.model;

import com.baedang.global.error.ErrorCode;
import com.baedang.trading.dto.OrderResponse;

/** REJECTED 행도 커밋한 뒤 HTTP 오류로 변환할 수 있게 트랜잭션 경계를 넘기는 결과입니다. */
public record MarketOrderResult(OrderResponse response, ErrorCode rejectionReason) {

    public static MarketOrderResult filled(OrderResponse response) {
        return new MarketOrderResult(response, null);
    }

    public static MarketOrderResult rejected(ErrorCode reason) {
        return new MarketOrderResult(null, reason);
    }

    public boolean rejected() {
        return rejectionReason != null;
    }
}
