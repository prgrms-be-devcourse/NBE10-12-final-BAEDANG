package com.baedang.trading.model;

/** 실행 횟수와 book revision은 재시도 시 중복 체결을 막는 낙관적 준비 토큰입니다. */
public record LimitExecutionAttempt(Long accountId, Long orderId, Long stockId,
        int executionCount, Long bookVersion, Long revision, OrderMarketContext context) {
    public LimitExecutionAttempt {
        if (accountId == null || accountId <= 0 || orderId == null || orderId <= 0 || stockId == null || stockId <= 0
                || executionCount < 0 || bookVersion == null || bookVersion <= 0 || revision == null || revision < 0
                || context == null) throw new IllegalArgumentException("체결 시도의 계좌·주문·버전 근거가 필요합니다");
    }
}
