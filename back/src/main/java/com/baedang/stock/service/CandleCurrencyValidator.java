package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.Candle;
import com.baedang.stock.entity.Stock;

import java.util.List;

/**
 * Toss가 내려준 캔들의 통화가 우리 {@code stock.currency}와 일치하는지 검증한다.
 * 어긋나면 심볼이 다른 시장에 잘못 매핑된 것이라 저장하면 안 된다.
 *
 * <p>온디맨드 조회({@link CandleQueryService})와 상위 100 배치 수집
 * ({@link MinuteCandleCollectionService})이 같은 검증을 쓴다 — 중복 구현을
 * 피하려고 공용 헬퍼로 뺐다.
 */
final class CandleCurrencyValidator {

    private CandleCurrencyValidator() {
    }

    static void validate(Stock stock, List<Candle> candles) {
        boolean mismatch = candles.stream().anyMatch(candle ->
                candle.currency() == null
                        || stock.getCurrency() == null
                        || !stock.getCurrency().equalsIgnoreCase(candle.currency().trim()));
        if (mismatch) {
            throw new BusinessException(
                    ErrorCode.QUOTE_CURRENCY_MISMATCH,
                    "symbol=" + stock.getSymbol());
        }
    }
}
