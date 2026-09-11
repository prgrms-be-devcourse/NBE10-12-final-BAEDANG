package com.baedang.market.model;

/**
 * 누락된 종가·기준가 복구 결과입니다.
 *
 * @param targetCount 랭킹 및 활성 지정가 주문의 대상 종목 수
 * @param updatedCount 실제로 갱신된 시세 스냅샷 수
 */
public record PrevCloseUpdateResult(
        int targetCount,
        int updatedCount
) {

    public int skippedCount() {
        return targetCount - updatedCount;
    }
}
