package com.baedang.global.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 컨트롤러에서 터진 예외를 {@link ErrorResponse} 로 바꿔주는 곳.
 *
 * <p>이게 있으면 컨트롤러마다 try-catch 를 쓸 필요가 없습니다.
 * 서비스에서 {@code throw new BusinessException(ErrorCode.INSUFFICIENT_CASH)} 만 하면
 * 알아서 422 와 문구가 나갑니다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 예상한 실패. 로그는 WARN 으로 남깁니다 — 버그가 아니라 정상 동작입니다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("[{}] {}", code.name(), e.getMessage());
        ErrorResponse response = e.getData() == null
                ? ErrorResponse.of(code)
                : ErrorResponse.of(code, e.getData());
        return ResponseEntity.status(code.getStatus()).body(response);
    }

    /** @Valid 검증 실패. 어느 필드가 왜 틀렸는지 data 에 담아줍니다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        log.warn("[INVALID_INPUT] {}", fields);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, fields));
    }

    /** 필수 헤더 누락과 읽을 수 없는 JSON은 서버 장애가 아니라 잘못된 요청입니다. */
    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception e) {
        log.warn("[INVALID_INPUT] {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }

    /**
     * 예상 못 한 예외. 여기 걸리면 <b>버그입니다.</b>
     * 스택트레이스를 남기되, 사용자에게는 내부 사정을 보여주지 않습니다 —
     * 예외 메시지에 테이블명이나 쿼리가 섞여 나가면 그 자체가 정보 노출입니다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리하지 못한 예외", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }
}
