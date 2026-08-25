package com.baedang.trading.model;

import java.math.BigDecimal;

/** 시장가 주문의 수수료·세금 계산 결과를 나타내는 불변 값 객체입니다. */
public record OrderAmount(
        BigDecimal executedPrice,
        BigDecimal exchangeRate,
        BigDecimal grossAmount,
        BigDecimal fee,
        BigDecimal tax,
        BigDecimal netAmount
) {
}
