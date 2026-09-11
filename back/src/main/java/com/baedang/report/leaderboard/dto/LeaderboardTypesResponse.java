package com.baedang.report.leaderboard.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 유형별 성과 비교({@code GET /api/reports/leaderboard/types}). 최신 배치 스냅샷의 같은 라운드
 * 코호트에서 유형별 평균 수익률을 비교한다(설계문서 §7).
 *
 * <p>사행성 배제: 절대 금액이 아니라 수익률%(0~1 소수 문자열)만 노출한다 — 코호트 내 초기자본이
 * 동일 상수라 평균 이익금 순위 = 평균 수익률 순위와 같다. 미분류(유형 NULL)는 비교에서 제외한다.
 * 아침 배치 기준이라 리포트(live)와 유형이 다를 수 있어 {@code asOf} 를 함께 내린다.
 */
public record LeaderboardTypesResponse(
        OffsetDateTime asOf,
        List<TypeEntry> types
) {

    public record TypeEntry(
            String typeCode,
            String typeLabel,
            long count,
            String avgReturnRate
    ) {
    }

    public static LeaderboardTypesResponse empty() {
        return new LeaderboardTypesResponse(null, List.of());
    }
}
