package com.baedang.report.leaderboard.dto;

/**
 * 유형별 집계 프로젝션(최신 as_of 코호트). JPQL 생성자 표현식으로 채운다. 평균 수익률은 JPQL
 * {@code avg}가 Double 로 주므로 그대로 받고, 표시 단계에서 수익률%로 포맷한다.
 */
public record TypeAggregate(
        String typeCode,
        long count,
        double avgReturnRate
) {
}
