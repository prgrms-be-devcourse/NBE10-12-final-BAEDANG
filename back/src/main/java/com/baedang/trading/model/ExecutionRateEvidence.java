package com.baedang.trading.model;

import com.baedang.market.port.ExecutionExchangeRateSnapshot;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 트랜잭션에 전달할 환율 근거. 수신·유효 시작·종료 시각은 모두 필수이며 DB에는 실제 적용 환율만 보존합니다. */
public record ExecutionRateEvidence(BigDecimal rate, OffsetDateTime fetchedAt,
                                    OffsetDateTime validFrom, OffsetDateTime validUntil) {
    public ExecutionRateEvidence {
        if (rate == null || rate.signum() <= 0) throw new IllegalArgumentException("환율은 양수여야 합니다");
        if (fetchedAt == null || validFrom == null || validUntil == null
                || !validFrom.isBefore(validUntil)) {
            throw new IllegalArgumentException("환율 유효 근거는 모두 제공해야 합니다");
        }
    }

    public static ExecutionRateEvidence from(ExecutionExchangeRateSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("환율 스냅샷이 필요합니다");
        return new ExecutionRateEvidence(snapshot.rate(), snapshot.fetchedAt(), snapshot.validFrom(), snapshot.validUntil());
    }

    /** 국내는 외부 환율 조회 없이 준비 시각 기준의 환율 1 근거를 생성합니다. */
    public static ExecutionRateEvidence krw(OffsetDateTime preparedAt) {
        if (preparedAt == null) throw new IllegalArgumentException("준비 시각이 필요합니다");
        return new ExecutionRateEvidence(BigDecimal.ONE, preparedAt, preparedAt,
                preparedAt.plusSeconds(60));
    }

    public boolean isValidAt(OffsetDateTime at) {
        return at != null && !at.isBefore(fetchedAt)
                && !at.isBefore(validFrom) && at.isBefore(validUntil);
    }
}
