package com.baedang.trading.model;

import com.baedang.trading.dto.OrderDetailResponse;
import com.baedang.trading.entity.OrderStatus;

import java.util.Map;
import java.util.Objects;

/**
 * 지정가 접수·재생 결과. REJECTED의 이벤트 데이터를 트랜잭션 경계 밖 서비스 계층으로 넘깁니다.
 *
 * <p>{@code close}(취소·만료)는 CB 메타데이터를 전달하지 않으므로 {@link OrderDetailResponse}를
 * 그대로 반환합니다. 이 wrapper는 접수와 멱등 재생에만 사용합니다.
 */
public record LimitOrderResult(
        OrderDetailResponse response,
        Map<String, Object> rejectionData
) {

    public LimitOrderResult {
        Objects.requireNonNull(response, "response must not be null");
        rejectionData = rejectionData == null ? Map.of() : Map.copyOf(rejectionData);
    }

    public static LimitOrderResult normal(OrderDetailResponse response) {
        return new LimitOrderResult(response, Map.of());
    }

    public static LimitOrderResult rejected(OrderDetailResponse response, Map<String, Object> data) {
        return new LimitOrderResult(response, data);
    }

    public boolean rejected() {
        return response.status() == OrderStatus.REJECTED;
    }
}
