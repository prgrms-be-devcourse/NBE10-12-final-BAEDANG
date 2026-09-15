package com.baedang.trading.model;

import java.math.BigDecimal;

/** releasedCash는 결제액과 별개로 추가 해제할 미사용 잔액입니다. 보류하면 기존 동결을 유지합니다. */
public record BuyReservationResult(boolean executable, BigDecimal reservedCashAfter, BigDecimal releasedCash) {
    public BuyReservationResult {
        if (reservedCashAfter == null || releasedCash == null
                || reservedCashAfter.signum() < 0 || releasedCash.signum() < 0
                || (!executable && releasedCash.signum() != 0)) {
            throw new IllegalArgumentException("동결 계산 결과가 올바르지 않습니다");
        }
    }
}
