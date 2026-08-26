package com.baedang.stock.port;

/**
 * 외부 API와 무관한 종목 유니버스 항목 모델.
 *
 * 주간 종목 마스터 갱신 배치(현재는 수동 실행)가 사용한다 — 심볼 목록 확보 후
 * {@link SymbolInfoPort#fetchStocks(List)}로 상세를 채운다.
 */
public record StockUniverseEntry(
        String symbol,
        String name,
        String securityType,
        Boolean isCommonShare,
        String isisCode
) {
}
