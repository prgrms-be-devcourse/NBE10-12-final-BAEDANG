package com.baedang.account.support;

import java.math.BigDecimal;

/**
 * 보유 종목 한 건의 원화 평가 결과. {@code HoldingValuator} 가 계산합니다.
 *
 * <p>단가({@code avgBuyPrice}·{@code lastPrice})는 <b>종목 통화 그대로</b> 담고,
 * 원화 환산값은 {@code costWon}·{@code evalWon} 두 개만 둡니다.
 * 화면의 원화 단가 표기는 프론트가 환율로 환산합니다.
 *
 * <p>계좌 요약(#1)은 {@code evalWon}·{@code costWon} 합만 쓰고,
 * 보유 목록(#2)이 나머지 필드를 재사용합니다.
 */
public record HoldingValuation(
        Long stockId,
        String currency,
        BigDecimal quantity,
        BigDecimal avgBuyPrice,
        BigDecimal avgExchangeRate,
        BigDecimal lastPrice,
        BigDecimal evalWon,
        BigDecimal costWon
) {

    /** 종목 평가손익(원). eval − cost. */
    public BigDecimal pnlWon() {
        return evalWon.subtract(costWon);
    }
}
