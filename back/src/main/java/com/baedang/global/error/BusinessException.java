package com.baedang.global.error;

/**
 * 비즈니스 규칙 위반을 나타내는 예외.
 *
 * <p>"예수금이 부족하다", "장이 닫혔다" 처럼 <b>예상 가능한</b> 실패에 씁니다.
 * NullPointerException 같은 버그성 예외와 구분하기 위한 것입니다 —
 * 이건 잡아서 사용자에게 문구를 보여주면 되고, 저건 로그를 남기고 고쳐야 합니다.
 *
 * <p>{@code detail} 은 개발자용입니다. 로그에만 남고 응답에는 나가지 않습니다.
 * "어느 계좌가 얼마 부족했는지" 같은 걸 담아두면 디버깅이 훨씬 쉬워집니다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = null;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + " (" + detail + ")");
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getDetail() {
        return detail;
    }
}
