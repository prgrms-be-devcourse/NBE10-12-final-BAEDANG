package com.baedang.market.port;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
/**
 * 외부 시세 제공자와 무관한 현재가 모델.
 *
 * @param symbol 종목 심볼
 * @param lastPrice 현재가
 * @param quoteAt 시세 기준 시각. 체결 미발생 시 null
 * @param currency 가격 통화
 */
public record PriceQuote (
        String symbol,
        BigDecimal lastPrice,
        OffsetDateTime quoteAt,
        String currency
) {
}
