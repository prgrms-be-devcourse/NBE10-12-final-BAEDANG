package com.baedang.stock.port;

import java.util.List;

/**
 * 종목 기본 정보 조회 Port.
 *
 * 외부 API의 존재나 HTTP 호출 방식을 노출하지 않는다.
 */
public interface SymbolInfoPort {

    /**
     * 여러 종목의 기본 정보를 조회한다.
     *
     * @param symbols 조회할 종목 심볼 목록 (최대 200개)
     * @return 종목 기본 정보 목록. 상장예정(SCHEDULED) 종목은 제외되어
     *         반환 크기가 요청 심볼 수보다 작을 수 있다
     */
    List<StockInfo> fetchStocks(List<String> symbols);

    /**
     * 한 종목의 매수 유의사항을 조회한다.
     *
     * 상세 페이지 전용 단건 조회다 — 여러 종목 순회가 필요하면
     * Service/배치가 반복 호출한다.
     *
     * @param symbol 종목 심볼
     * @return 활성 유의사항 묶음. 경고가 없으면 빈 목록
     */
    StockWarnings fetchStockWarnings(String symbol);

}
