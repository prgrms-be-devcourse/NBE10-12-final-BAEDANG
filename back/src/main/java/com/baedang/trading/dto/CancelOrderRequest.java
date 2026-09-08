package com.baedang.trading.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * 주문 취소 요청 DTO.
 *
 * <p>주문 취소는 오직 상태를 CANCELED로 전이시키는 단일 목적 명령이므로,
 * 가격이나 수량 등 다른 필드의 임의 정정/유입을 엄격히 차단합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CancelOrderRequest(
        @NotBlank(message = "status는 필수입니다")
        @Pattern(regexp = "CANCELED", message = "status는 CANCELED만 허용됩니다")
        String status,
        @JsonAnySetter
        Map<String, Object> extra
) {
    public CancelOrderRequest {
        if (extra != null && !extra.isEmpty()) {
            throw new IllegalArgumentException("허용되지 않은 추가 필드가 포함되어 있습니다");
        }
    }

    public CancelOrderRequest(String status) {
        this(status, null);
    }
}
