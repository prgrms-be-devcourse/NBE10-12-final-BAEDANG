package com.baedang.trading.model;

import com.baedang.trading.entity.OrderSide;
import java.math.BigDecimal;

import static com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale;

/** 한 체결의 정산 차액과 원본 금액. 누적 차액 산출은 정산 계산기가 담당합니다. */
public record ExecutionAmounts(BigDecimal grossAmountUsd, BigDecimal unroundedGrossAmountKrw,
                               BigDecimal secFeeUsd,
                               BigDecimal grossAmountKrw, BigDecimal feeKrw, BigDecimal taxKrw, BigDecimal netAmountKrw) {
    public ExecutionAmounts {
        BigDecimal[] values = {grossAmountUsd, unroundedGrossAmountKrw, secFeeUsd,
                grossAmountKrw, feeKrw, taxKrw, netAmountKrw};
        // 앞의 두 원본 거래대금은 반올림하지 않으며, SEC는 센트·확정 원화 금액은 원 단위입니다.
        for (int i = 0; i < values.length; i++) {
            BigDecimal value = values[i];
            if (value == null || value.signum() < 0 || (i >= 2 && !isRepresentableAtScale(value, i == 2 ? 2 : 0))) {
                throw new IllegalArgumentException("체결 금액의 값 또는 소수 자릿수가 올바르지 않습니다");
            }
        }
    }

    public void validateSide(OrderSide side) {
        BigDecimal expected = side == OrderSide.BUY ? grossAmountKrw.add(feeKrw)
                : grossAmountKrw.subtract(feeKrw).subtract(taxKrw);
        if (side == null || expected.compareTo(netAmountKrw) != 0
                || (side == OrderSide.BUY && (taxKrw.signum() != 0 || secFeeUsd.signum() != 0))) {
            throw new IllegalArgumentException("체결 방향과 정산 금액이 일치하지 않습니다");
        }
    }
}
