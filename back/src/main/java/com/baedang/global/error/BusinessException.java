package com.baedang.global.error;

import java.util.Map;

/**
 * 비즈니스 규칙 위반을 나타내는 예외.
 *
 * <p>"예수금이 부족하다", "장이 닫혔다" 처럼 <b>예상 가능한</b> 실패에 씁니다.
 * NullPointerException 같은 버그성 예외와 구분하기 위한 것입니다 —
 * 이건 잡아서 사용자에게 문구를 보여주면 되고, 저건 로그를 남기고 고쳐야 합니다.
 *
 * <p>{@code detail} 은 개발자용입니다. 로그에만 남고 응답에는 나가지 않습니다.
 * "어느 계좌가 얼마 부족했는지" 같은 걸 담아두면 디버깅이 훨씬 쉬워집니다.
 * {@code data} 는 필드명이나 재시도 정책처럼 클라이언트가 분기할 구조화 정보만 담습니다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;
    private final Map<String, Object> data;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = null;
        this.data = null;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + " (" + detail + ")");
        this.errorCode = errorCode;
        this.detail = detail;
        this.data = null;
    }

    public BusinessException(ErrorCode errorCode, Map<String, Object> data) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = null;
        this.data = data == null ? null : Map.copyOf(data);
    }

    public BusinessException(ErrorCode errorCode, String detail, Map<String, Object> data) {
        super(errorCode.getMessage() + " (" + detail + ")");
        this.errorCode = errorCode;
        this.detail = detail;
        this.data = data == null ? null : Map.copyOf(data);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getDetail() {
        return detail;
    }

    public Map<String, Object> getData() {
        return data;
    }
}
