package com.baedang.market.port;

import java.util.List;

/**
 * 시장 데이터 조회 Port.
 * 외부 시세 제공자나 HTTP 호출 방식을 노출하지 않는다.
 */
public interface MarketDataPort {

    /** 여러 종목의 현재가를 조회한다. */
    List<PriceQuote> fetchPrices(List<String> symbols);
    /**
     * 특정 종목의 캔들을 요청한 개수만큼 조회한다.
     * Toss의 단일 요청 최대 200개 제한과 before 페이지네이션은
     * Adapter가 처리한다.
     */
    List<Candle> fetchCandles(String symbol, CandleInterval interval, int count);
}
