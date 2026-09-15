package com.baedang.report.leaderboard.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 리더보드 응답({@code GET /api/reports/leaderboard}). 아침 배치 스냅샷 기준이라 리포트(live)와
 * 값이 다를 수 있어 {@code asOf} 를 함께 내린다(신선도 스큐 방지, 설계문서 §6.4).
 *
 * <p>사행성 배제(§3.1): 절대 금액·타인 잔고는 노출하지 않고 수익률%(0~1 소수 문자열)와 순위·
 * 상위 % 만 노출한다. 닉네임은 가운데 글자 마스킹(§6.5).
 *
 * <p>스냅샷이 아직 없거나 자격 계좌가 0이면 {@code asOf=null·top=[]·me=null}(빈/잠금 보드).
 * 내 자격 계좌가 없거나 스냅샷에 없으면 {@code me=null}(top 은 그대로).
 */
public record LeaderboardResponse(
        OffsetDateTime asOf,
        int participants,
        List<Entry> top,
        MeSection me
) {

    /** 순위표 한 줄. */
    public record Entry(
            int rank,
            String nickname,
            String returnRate
    ) {
    }

    /**
     * 내 순위 구간. {@code topPercent} 는 1/5/10/25/50/75 중 하나이거나 하위권이면 null.
     * 유형 관련 필드(typeCode·typeLabel·typeRank·typeParticipants·typePercent)는 내 유형 내 순위다 —
     * 미분류(유형 없음)면 모두 null.
     */
    public record MeSection(
            int rank,
            String returnRate,
            Integer topPercent,
            List<Entry> neighbors,
            String typeCode,
            String typeLabel,
            Integer typeRank,
            Integer typeParticipants,
            Integer typePercent
    ) {
    }

    public static LeaderboardResponse empty() {
        return new LeaderboardResponse(null, 0, List.of(), null);
    }
}
