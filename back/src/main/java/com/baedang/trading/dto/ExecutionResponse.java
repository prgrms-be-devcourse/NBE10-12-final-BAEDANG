package com.baedang.trading.dto;

import java.time.OffsetDateTime;

/** 체결 단가는 시장 통화(KR: KRW, US: USD), 정산 금액과 잔액은 KRW입니다. */
public record ExecutionResponse(
        Long executionId,
        int sequenceNo,
        String quantity,
        String price,
        String exchangeRate,
        String grossAmount,
        String fee,
        String tax,
        String netAmount,
        String balanceAfter,
        OffsetDateTime executedAt
) {}
