package com.baedang.trading.model;

import com.baedang.global.error.ErrorCode;

import java.util.Map;

/**
 * REJECTED 행도 커밋한 뒤 HTTP 오류로 변환할 수 있게 트랜잭션 경계를 넘기는 결과입니다.
 *
 * <p>{@code rejectionData}는 거절 사유에 딸린 구조화 정보입니다. 서킷브레이커 거절이면
 * {@code market}/{@code eventType}/{@code stage}/{@code triggeredAt}/{@code haltUntil}이 담기고,
 * 서비스 계층이 여기에 {@code retryPolicy}를 더해 응답을 만듭니다. Map.copyOf로 동결해 트랜잭션
 * 커밋 이후 호출부가 값을 바꾸지 못하게 합니다.
 */
public record MarketOrderResult(
        MarketOrderReceipt receipt,
        ErrorCode rejectionReason,
        Map<String, Object> rejectionData
) {

    public MarketOrderResult {
        rejectionData = rejectionData == null ? Map.of() : Map.copyOf(rejectionData);
    }

    public static MarketOrderResult filled(MarketOrderReceipt receipt) {
        return new MarketOrderResult(receipt, null, Map.of());
    }

    public static MarketOrderResult rejected(ErrorCode reason) {
        return new MarketOrderResult(null, reason, Map.of());
    }

    public static MarketOrderResult rejected(ErrorCode reason, Map<String, Object> data) {
        return new MarketOrderResult(null, reason, data);
    }

    public boolean rejected() {
        return rejectionReason != null;
    }
}
