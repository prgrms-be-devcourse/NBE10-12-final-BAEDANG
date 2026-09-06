package com.baedang.trading.support;

import java.math.BigDecimal;

/**
 * PostgreSQL NUMERIC 컬럼의 정수부 상한 상수.
 * 스키마의 precision/scale이 바뀌면 이 값도 함께 갱신합니다.
 */
public final class NumericBounds {
    /** NUMERIC(19,4) — 금액/단가 정수부는 10^15 미만 */
    public static final BigDecimal MONEY_LIMIT = new BigDecimal("1000000000000000");
    /** NUMERIC(19,6) — 수량/환율 정수부는 10^13 미만 */
    public static final BigDecimal RATE_LIMIT = new BigDecimal("10000000000000");

    private NumericBounds() {}
}
