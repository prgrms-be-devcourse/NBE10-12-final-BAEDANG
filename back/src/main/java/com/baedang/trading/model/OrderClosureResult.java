package com.baedang.trading.model;

import java.math.BigDecimal;

/** 주문 종료 직전의 해제량. 호출부는 계좌 잠금 아래 같은 트랜잭션에서 실제 동결을 해제합니다. */
public record OrderClosureResult(boolean changed, BigDecimal releasedCash, BigDecimal releasedQuantity) {
    public static OrderClosureResult unchanged() {
        return new OrderClosureResult(false, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
