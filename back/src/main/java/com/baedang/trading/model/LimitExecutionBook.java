package com.baedang.trading.model;

import java.time.Instant;
import java.util.List;

/** 한 SQL statement에서 읽은 버전 헤더와 잔량입니다. 미리보기는 이를 소비하지 않습니다. */
public record LimitExecutionBook(Long version, Long revision, Instant quoteAt, Instant generatedAt,
        List<LimitExecutionPlan.Level> levels) {
    public LimitExecutionBook { levels = List.copyOf(levels); }
}
