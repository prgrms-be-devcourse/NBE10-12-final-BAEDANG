package com.baedang.trading.model;

import com.baedang.global.error.ErrorCode;
/** REJECTED 행도 커밋한 뒤 HTTP 오류로 변환할 수 있게 트랜잭션 경계를 넘기는 결과입니다. */
public record MarketOrderResult(MarketOrderReceipt receipt, ErrorCode rejectionReason) {

    public static MarketOrderResult filled(MarketOrderReceipt receipt) {
        return new MarketOrderResult(receipt, null);
    }

    public static MarketOrderResult rejected(ErrorCode reason) {
        return new MarketOrderResult(null, reason);
    }

    public boolean rejected() {
        return rejectionReason != null;
    }
}
