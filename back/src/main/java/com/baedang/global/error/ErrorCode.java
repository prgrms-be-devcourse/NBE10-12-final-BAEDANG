package com.baedang.global.error;

import org.springframework.http.HttpStatus;

/**
 * 서비스 전체의 에러 코드.
 *
 * <p>API 명세서(docs/api-spec.html)의 에러 표를 그대로 옮긴 것입니다.
 * 명세서를 고치면 여기도 같이 고쳐주세요. 반대도 마찬가지입니다.
 *
 * <p><b>왜 enum 하나로 모으는가</b><br>
 * 예외를 던지는 곳마다 문자열과 상태코드를 직접 쓰면, 같은 상황인데
 * 사람마다 다른 문구가 나갑니다. 화면 문구를 여기 한 곳에 모아두면
 * 기획이 문구를 바꿔달라고 할 때 한 줄만 고치면 됩니다.
 *
 * <p><b>message 는 사용자에게 그대로 보여줄 문장입니다.</b><br>
 * 개발자용 상세 정보는 {@link BusinessException} 의 detail 에 담으세요.
 * 사용자에게 "NullPointerException" 을 보여주는 일은 없어야 합니다.
 *
 * <p><b>팀원 각자 자유롭게 추가하세요.</b><br>
 * 도메인별로 구획을 나눠뒀습니다. 자기 도메인 구획 안에 추가하면
 * 다른 사람 작업과 충돌이 거의 나지 않습니다.
 * 코드 이름은 {@code 대문자_스네이크}, 화면 문구는 존댓말로 통일합니다.
 */
public enum ErrorCode {

    // ── 공통 ────────────────────────────────────────────────────────────────
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않아요"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식이에요"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했어요. 잠시 후 다시 시도해주세요"),

    // ── 인증 · 회원 ─────────────────────────────────────────────────────────
    //   1주차에는 토큰을 발급하지 않으므로 UNAUTHORIZED 는 아직 쓰이지 않습니다.
    //   2주차에 JWT 를 붙일 때부터 사용하세요.
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일이에요"),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임이에요"),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않아요"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원 정보를 찾을 수 없어요"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요"),

    // ── 종목 ────────────────────────────────────────────────────────────────
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 종목이에요"),
    INVALID_QUERY(HttpStatus.BAD_REQUEST, "검색어는 2자 이상 입력해주세요"),
    INVALID_INTERVAL_RANGE(HttpStatus.BAD_REQUEST, "지원하지 않는 차트 기간 조합이에요"),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "잘못된 페이지 정보예요. 처음부터 다시 불러와주세요"),

    // ── 시세 ────────────────────────────────────────────────────────────────
    QUOTE_NOT_FOUND(HttpStatus.NOT_FOUND, "시세 정보를 가져올 수 없어요"),
    QUOTE_CURRENCY_MISMATCH(HttpStatus.BAD_GATEWAY, "시세 통화 정보가 올바르지 않아요"),
    EXCHANGE_RATE_NOT_FOUND(HttpStatus.NOT_FOUND, "환율 정보를 가져올 수 없어요"),

    // ── 주문 · 체결 ─────────────────────────────────────────────────────────
    MARKET_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "지금은 거래할 수 없는 시간이에요"),
    MARKET_CONTEXT_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "시장 정보를 다시 확인한 뒤 주문해주세요"),
    NOT_IN_UNIVERSE(HttpStatus.UNPROCESSABLE_ENTITY, "이 종목은 아직 거래를 지원하지 않아요"),
    STOCK_SUSPENDED(HttpStatus.UNPROCESSABLE_ENTITY, "거래정지 종목이에요"),
    STOCK_LIQUIDATION(HttpStatus.UNPROCESSABLE_ENTITY, "정리매매 종목이에요"),
    INSUFFICIENT_CASH(HttpStatus.UNPROCESSABLE_ENTITY, "주문가능금액이 부족해요"),
    INSUFFICIENT_QUANTITY(HttpStatus.UNPROCESSABLE_ENTITY, "보유 수량이 부족해요"),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "수량은 1주 이상의 정수로 입력해주세요"),

    /**
     * 시세가 너무 오래됐을 때. 기준은 {@code trading.quote-max-staleness-seconds}(15초).
     * 오래된 가격으로 체결되면 원장의 신뢰가 무너지므로 차라리 거절합니다.
     */
    STALE_QUOTE(HttpStatus.UNPROCESSABLE_ENTITY, "시세 정보가 오래되었어요. 다시 시도해주세요"),
    FUTURE_QUOTE(HttpStatus.UNPROCESSABLE_ENTITY, "시세 기준 시각이 올바르지 않아요. 다시 시도해주세요"),
    INVALID_SETTLEMENT_AMOUNT(HttpStatus.UNPROCESSABLE_ENTITY, "정산 금액이 올바르지 않아요"),

    /**
     * 같은 {@code clientOrderId} 로 이미 처리된 주문. 중복 클릭이거나 네트워크 재시도입니다.
     * 에러로 보이지만 실제로는 <b>기존 주문 결과를 그대로 돌려주는 것</b>이 맞습니다.
     * 서비스에서 이 코드를 던지기 전에 기존 주문 조회를 먼저 시도하세요.
     */
    DUPLICATE_ORDER(HttpStatus.CONFLICT, "이미 처리된 주문이에요"),

    // ── 계좌 ────────────────────────────────────────────────────────────────
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌 정보를 찾을 수 없어요"),
    ACCOUNT_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "종료된 회차의 계좌예요"),

    // ── 외부 API ────────────────────────────────────────────────────────────
    TOSS_API_ERROR(HttpStatus.BAD_GATEWAY, "시세 서버와 통신할 수 없어요"),
    TOSS_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 후 다시 시도해주세요");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
