package com.baedang.trading.model;

import java.math.BigDecimal;

/** 저장된 체결의 합계 또는 같은 트랜잭션에서 앞서 채택한 후보까지의 합계. DB 컬럼이 아닙니다. */
public record CumulativeSettlementState(BigDecimal quantity, BigDecimal nativeGrossAmount,
                                        BigDecimal unroundedGrossAmountKrw, BigDecimal secFeeUsd,
                                        BigDecimal unroundedTaxKrw, BigDecimal grossAmountKrw,
                                        BigDecimal feeKrw, BigDecimal taxKrw) {
    public CumulativeSettlementState {
        BigDecimal[] values = {quantity, nativeGrossAmount, unroundedGrossAmountKrw, secFeeUsd,
                unroundedTaxKrw, grossAmountKrw, feeKrw, taxKrw};
        for (BigDecimal value : values) {
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException("누적 정산 근거는 0 이상의 값이어야 합니다");
            }
        }
    }

    public static CumulativeSettlementState empty() {
        return new CumulativeSettlementState(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
