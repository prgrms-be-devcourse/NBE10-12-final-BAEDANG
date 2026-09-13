package com.baedang.report.leaderboard.dto;

import java.math.BigDecimal;

/**
 * 유형별 집계 프로젝션(최신 as_of 코호트). 네이티브 쿼리의 {@code avg(numeric)} 결과를
 * BigDecimal로 받아 정밀도를 유지하고, 표시 단계에서만 반올림한다.
 */
public record TypeAggregate(
        String typeCode,
        long count,
        BigDecimal avgReturnRate
) {
}
