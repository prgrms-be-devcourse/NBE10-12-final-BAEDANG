package com.baedang.trading.support;

import java.math.BigDecimal;

/** 반올림 없이 표현 가능한 소수 자릿수만 검증합니다. 입력값과 스케일은 변경하지 않습니다. */
public final class DecimalScaleValidator {
    private DecimalScaleValidator() { }

    public static boolean isRepresentableAtScale(BigDecimal value, int scale) {
        return value != null && value.stripTrailingZeros().scale() <= scale;
    }
}
