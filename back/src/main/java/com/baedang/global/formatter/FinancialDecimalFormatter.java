package com.baedang.global.formatter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 금융 API 응답의 BigDecimal 값을 문자열로 변환합니다.
 *
 * 계산이나 저장에 사용할 BigDecimal을 생성하지 않으며, 모든 금융 계산이 끝난 뒤
 * 응답 또는 설명 문자열을 만드는 경계에서만 사용합니다.
 */
public final class FinancialDecimalFormatter {

    private static final int USD_SCALE = 2;
    private static final int KRW_SCALE = 0;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private FinancialDecimalFormatter() {
    }

    /** 일반 수량·비율을 지수 표기와 불필요한 후행 0 없이 표현합니다. */
    public static String plain(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    /** 환율처럼 입력값의 소수 자릿수를 보존해야 하는 값을 지수 표기 없이 표현합니다. */
    public static String preserveScale(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** USD 금액을 센트 단위로 반올림하고 소수점 두 자리로 표현합니다. */
    public static String usd(BigDecimal value) {
        if (value == null) return null;
        return value.setScale(USD_SCALE, MONEY_ROUNDING).toPlainString();
    }

    /** KRW 금액을 원 단위로 반올림하고 소수점 없이 표현합니다. */
    public static String krw(BigDecimal value) {
        if (value == null) return null;
        return value.setScale(KRW_SCALE, MONEY_ROUNDING).toPlainString();
    }

    /** 종목 통화가 명시된 가격을 KRW 또는 USD 표시 정책에 맞게 표현합니다. */
    public static String currency(BigDecimal value, String currency) {
        if (value == null) return null;
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        return switch (currency.trim().toUpperCase(Locale.ROOT)) {
            case "KRW" -> krw(value);
            case "USD" -> usd(value);
            default -> throw new IllegalArgumentException("Unsupported currency: " + currency);
        };
    }
}
