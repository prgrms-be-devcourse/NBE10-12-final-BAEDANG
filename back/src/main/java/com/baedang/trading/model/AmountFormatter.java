package com.baedang.trading.model;

import java.math.BigDecimal;

/** API와 원장 설명에서 금액을 지수 표기 없이 동일하게 표현합니다. */
public final class AmountFormatter {

    private AmountFormatter() {
    }

    public static String plain(BigDecimal value) {
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }
}
