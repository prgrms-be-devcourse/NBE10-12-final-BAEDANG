package com.baedang.market.model;

/**
 * 장 시작 전 전일 종가 갱신 결과입니다.
 *
 * @param targetCount 갱신 대상 상위 종목 수
 * @param updatedCount 실제로 갱신된 시세 스냅샷 수
 * @param fallbackCount 일봉 대신 기존 {@code last_price}를 사용한 수
 */
public record PrevCloseUpdateResult(
        int targetCount,
        int updatedCount,
        int fallbackCount
) {

    public int skippedCount() {
        return targetCount - updatedCount;
    }
}
