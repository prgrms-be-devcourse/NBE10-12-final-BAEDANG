package com.baedang.market.port;

import java.util.List;

/**
 * 시장 데이터 조회 Port.
 * 외부 시세 제공자나 HTTP 호출 방식을 노출하지 않는다.
 */
public interface MarketDataPort {
    List<PriceQuote> fetchPrices(List<String> symbols);
    List<Candle> fetchCandles(String symbols, CandleInterval interval, int count);
}
