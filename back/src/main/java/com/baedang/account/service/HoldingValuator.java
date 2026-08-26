package com.baedang.account.service;

import com.baedang.account.support.HoldingValuation;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.trading.entity.Holding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 보유 종목을 원화로 평가하는 순수 계산기 (DB·시각에 의존하지 않음).
 *
 * <p><b>반올림 규약</b> — 미국주식은 종목 통화(USD) 금액을 <b>센트($0.01)로 먼저 반올림</b>한 뒤
 * 환율을 곱하고 <b>1원으로 반올림</b>합니다. 한국주식은 곧장 1원으로 반올림합니다.
 * 체결·원장이 실현금액을 산정하는 규약과 같습니다 (모두 HALF_UP).
 *
 * <p><b>종목별로 반올림한 뒤 합산</b>합니다 — 계좌 요약의 stockValue 와
 * 보유 목록의 평가액 합이 같은 방식으로 떨어지도록 하기 위함입니다.
 *
 * <p>{@code quote_snapshot} 은 전 종목 상주라 시세가 비지 않지만, 방어적으로
 * 시세가 없으면 평가액을 원가로 보고 손익 0 으로 둡니다(엔드포인트를 죽이지 않음).
 */
@Component
public class HoldingValuator {

    private static final String KRW = "KRW";

    public List<HoldingValuation> valuate(
            List<Holding> holdings,
            Map<Long, QuoteSnapshot> quoteByStockId,
            BigDecimal usdKrwRate
    ) {
        return holdings.stream()
                .map(h -> valuateOne(h, quoteByStockId.get(h.getStockId()), usdKrwRate))
                .toList();
    }

    private HoldingValuation valuateOne(Holding holding, QuoteSnapshot quote, BigDecimal usdKrwRate) {
        boolean foreign = isForeign(holding, quote);
        String currency = quote != null ? quote.getCurrency() : (foreign ? "USD" : KRW);

        BigDecimal costRate = foreign ? holding.getAvgExchangeRate() : BigDecimal.ONE;
        BigDecimal costWon = toWon(holding.getQuantity(), holding.getAvgBuyPrice(), costRate, foreign);

        BigDecimal lastPrice = quote != null ? quote.getLastPrice() : null;
        BigDecimal evalWon;
        if (lastPrice == null) {
            // 시세 없음 → 원가로 평가(손익 0). 실제로는 거의 발생하지 않습니다.
            evalWon = costWon;
        } else {
            BigDecimal evalRate = foreign ? evalRate(usdKrwRate, holding.getAvgExchangeRate()) : BigDecimal.ONE;
            evalWon = toWon(holding.getQuantity(), lastPrice, evalRate, foreign);
        }

        return new HoldingValuation(
                holding.getStockId(),
                currency,
                holding.getQuantity(),
                holding.getAvgBuyPrice(),
                holding.getAvgExchangeRate(),
                lastPrice,
                evalWon,
                costWon
        );
    }

    /** 종목 통화 → 원화. foreign 이면 센트로 먼저 반올림한 뒤 환율 적용. */
    private BigDecimal toWon(BigDecimal quantity, BigDecimal nativePrice, BigDecimal rate, boolean foreign) {
        BigDecimal nativeAmount = quantity.multiply(nativePrice);
        if (foreign) {
            nativeAmount = nativeAmount.setScale(2, RoundingMode.HALF_UP);
        }
        return nativeAmount.multiply(rate).setScale(0, RoundingMode.HALF_UP);
    }

    /** 평가용 환율. 최신 환율이 없으면(부트스트랩 극초기) 매입 환율로 폴백. */
    private BigDecimal evalRate(BigDecimal usdKrwRate, BigDecimal avgExchangeRate) {
        return usdKrwRate != null ? usdKrwRate : avgExchangeRate;
    }

    /**
     * 외화 종목 여부. 시세가 있으면 통화로, 없으면 매입 환율이 1 이 아닌지로 판정합니다.
     * (원화 종목은 avg_exchange_rate 가 항상 1)
     */
    private boolean isForeign(Holding holding, QuoteSnapshot quote) {
        if (quote != null) {
            return !KRW.equalsIgnoreCase(quote.getCurrency());
        }
        return holding.getAvgExchangeRate().compareTo(BigDecimal.ONE) != 0;
    }
}
