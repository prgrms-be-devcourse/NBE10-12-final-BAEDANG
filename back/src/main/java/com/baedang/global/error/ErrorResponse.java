package com.baedang.global.error;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 모든 에러 응답의 공통 형태.
 *
 * <pre>
 * {
 *   "code": "INSUFFICIENT_CASH",
 *   "message": "주문가능금액이 부족해요",
 *   "timestamp": "2026-08-23T14:02:11+09:00",
 *   "data": { "required": "2415242", "available": "1200000" }
 * }
 * </pre>
 *
 * <p><b>프론트는 {@code code} 로 분기하고 {@code message} 를 그대로 띄웁니다.</b>
 * message 를 파싱해서 분기하면 문구를 못 바꾸게 되므로 그러지 마세요.
 *
 * <p>{@code data} 는 화면에서 추가로 쓸 값이 있을 때만 채웁니다.
 * "얼마가 부족한지"처럼 문구에 끼워 넣을 숫자가 여기 들어갑니다.
 */
public record ErrorResponse(
        String code,
        String message,
        OffsetDateTime timestamp,
        Map<String, Object> data
) {
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), OffsetDateTime.now(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, Map<String, Object> data) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), OffsetDateTime.now(), data);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message, OffsetDateTime.now(), null);
    }
}
