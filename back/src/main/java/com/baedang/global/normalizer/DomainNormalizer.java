package com.baedang.global.normalizer;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 도메인 문자열의 표현만 정규화합니다. 필수값·허용값 검증과 오류 응답은 호출부가 담당합니다.
 * null은 그대로 반환하며, 빈 문자열을 기본값으로 바꾸거나 내부 공백을 임의로 제거하지 않습니다.
 */
public final class DomainNormalizer {

    private static final Pattern SEARCH_WHITESPACE = Pattern.compile("\\s+");

    private DomainNormalizer() {
    }

    public static String symbol(String value) {
        return upperCode(value);
    }

    public static String currency(String value) {
        return upperCode(value);
    }

    /** 기존 가입·로그인 정책에 따라 이메일 전체의 대소문자를 구분하지 않습니다. */
    public static String email(String value) {
        return lowerCode(value);
    }

    /** 검색 키에 한해서 내부 공백도 제거합니다. null 필드의 대체값은 검색 서비스에서 정합니다. */
    public static String searchKey(String value) {
        return value == null ? null : SEARCH_WHITESPACE.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 시장·주문 방향 등 대소문자를 구분하지 않는 코드용입니다. 토큰·커서에는 적용하지 않습니다. */
    public static String upperCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public static String lowerCode(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
