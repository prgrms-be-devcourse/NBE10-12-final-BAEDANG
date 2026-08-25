package com.baedang.market.port;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
/**
 * 외부 API와 무관한 캔들(OHLCV) 단일 봉 모델.
        *
        * @param candleAt 봉 시작 시각
 * @param openPrice 시가
 * @param highPrice 고가
 * @param lowPrice 저가
 * @param closePrice 종가
 * @param volume 거래량
 * @param currency 통화 (KRW, USD)
 */
public record Candle(
        OffsetDateTime candleAt,
        String openPrice,
        String highPrice,
        String lowPrice,
        String closePrice,
        String volume,
        String currency
) {
}
