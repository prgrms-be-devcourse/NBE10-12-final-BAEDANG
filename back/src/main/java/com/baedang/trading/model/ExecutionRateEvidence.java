package com.baedang.trading.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 트랜잭션에 전달할 환율과 유효성 검증 입력. 시각 메타데이터는 DB에 저장하지 않고 실제 적용 환율만 보존합니다. */
public record ExecutionRateEvidence(BigDecimal rate, OffsetDateTime fetchedAt,
                                    OffsetDateTime validFrom, OffsetDateTime validUntil) {
    public ExecutionRateEvidence {
        if (rate == null || rate.signum() <= 0) throw new IllegalArgumentException("환율은 양수여야 합니다");
        boolean unknown = fetchedAt == null && validFrom == null && validUntil == null;
        if (!unknown && (fetchedAt == null || validFrom == null || validUntil == null
                || !validFrom.isBefore(validUntil))) {
            throw new IllegalArgumentException("환율 유효 근거는 모두 제공해야 합니다");
        }
    }

    public static ExecutionRateEvidence rateOnly(BigDecimal rate) {
        return new ExecutionRateEvidence(rate, null, null, null);
    }

    public boolean isValidAt(OffsetDateTime at) {
        return at != null && fetchedAt != null && !at.isBefore(fetchedAt)
                && !at.isBefore(validFrom) && at.isBefore(validUntil);
    }
}
