package com.baedang.report.leaderboard.support;

/**
 * 순위를 상위 % 브래킷으로 매핑한다(설계문서 §12: 1/5/10/25/50/75). 절대 순위 대신 브래킷만
 * 노출해 코호트 크기와 무관하게 "상위 몇 %"를 보여준다.
 *
 * <p>규칙: {@code rank/participants × 100} 이 드는 <b>가장 작은 컷</b>을 고른다. 예) 참가자 100명,
 * rank 1 → 1%, rank 5 → 5%, rank 6 → 10%. 75% 컷도 넘으면(하위권) {@code null}.
 */
public final class PercentileBracket {

    private static final int[] CUTS = {1, 5, 10, 25, 50, 75};

    private PercentileBracket() {
    }

    public static Integer of(int rank, int participants) {
        if (participants <= 0 || rank <= 0) {
            return null;
        }
        double percentile = rank * 100.0 / participants;
        for (int cut : CUTS) {
            if (percentile <= cut) {
                return cut;
            }
        }
        return null;
    }
}
