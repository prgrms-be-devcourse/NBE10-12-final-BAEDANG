package com.baedang.trading.model;

import com.baedang.trading.entity.OrderSide;
import java.math.BigDecimal;

/** 한 체결의 정산 차액과 원본 금액. 누적 차액 산출은 정산 계산기가 담당합니다. */
public record ExecutionAmounts(BigDecimal grossAmountUsd, BigDecimal unroundedGrossAmountKrw,
                               BigDecimal secFeeUsd,
                               BigDecimal grossAmountKrw, BigDecimal feeKrw, BigDecimal taxKrw, BigDecimal netAmountKrw) {
    public ExecutionAmounts {
        for (BigDecimal value : new BigDecimal[]{grossAmountUsd, unroundedGrossAmountKrw, secFeeUsd,
                grossAmountKrw, feeKrw, taxKrw, netAmountKrw}) {
            if (value == null || value.signum() < 0) throw new IllegalArgumentException("체결 금액은 음수일 수 없습니다");
        }
        for (BigDecimal money : new BigDecimal[]{grossAmountKrw, feeKrw, taxKrw, netAmountKrw}) {
            money.setScale(0, java.math.RoundingMode.UNNECESSARY);
        }
        secFeeUsd.setScale(2, java.math.RoundingMode.UNNECESSARY);
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
