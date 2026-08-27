package com.baedang.account.dto;

import com.baedang.account.support.HoldingValuation;
import com.baedang.stock.entity.Stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 마이페이지 보유 종목 목록. {@code GET /accounts/me/holdings} 의 응답입니다.
 *
 * <p>평가금액 계열({@code evaluationAmount}·{@code unrealizedPnl})만 <b>백엔드가 원화로 계산</b>합니다.
 * 단가({@code avgBuyPrice}·{@code lastPrice})는 <b>종목 통화 그대로</b> 내려보내고,
 * 원화 표기는 프론트가 {@code avgExchangeRate}·최신 환율로 환산합니다.
 *
 * <p>금액은 전부 문자열입니다 — 프론트 number 의 배정밀도 오차를 피하려고 토스 API 처럼 문자열로 내립니다.
 * 값이 없는 필드({@code lastPrice}·{@code unrealizedPnlRate})는 null 이면 응답에서 빠집니다.
 */
public record HoldingsResponse(
        List<Item> items,
        OffsetDateTime asOf
) {

    /**
     * 보유 종목 한 건.
     *
     * @param realtime 시세가 살아 움직이는지(정규장 수집 중) 여부. {@code quote_at} 신선도로 판정합니다 —
     *                 장이 열려 있어도 랭킹에서 빠진 보유 종목은 시세가 정지되므로 {@code false} 가 맞습니다.
     */
    public record Item(
            String symbol,
            String name,
            String currency,
            String quantity,
            String avgBuyPrice,
            String avgExchangeRate,
            String lastPrice,
            String evaluationAmount,
            String unrealizedPnl,
            String unrealizedPnlRate,
            boolean realtime
    ) {

        public static Item of(HoldingValuation valuation, Stock stock, boolean realtime) {
            BigDecimal costWon = valuation.costWon();
            BigDecimal pnlWon = valuation.pnlWon();
            BigDecimal pnlRate = costWon.signum() > 0
                    ? pnlWon.divide(costWon, 4, RoundingMode.HALF_UP)
                    : null;

            return new Item(
                    stock.getSymbol(),
                    stock.getName(),
                    stock.getCurrency(),
                    plain(valuation.quantity()),
                    plain(valuation.avgBuyPrice()),
                    plain(valuation.avgExchangeRate()),
                    plainOrNull(valuation.lastPrice()),
                    plain(valuation.evalWon()),
                    plain(pnlWon),
                    plainOrNull(pnlRate),
                    realtime
            );
        }
    }

    private static String plain(BigDecimal value) {
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    private static String plainOrNull(BigDecimal value) {
        return value == null ? null : plain(value);
    }
}
