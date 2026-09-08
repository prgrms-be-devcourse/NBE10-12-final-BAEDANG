package com.baedang.trading.dto;

import java.util.List;

/**
 * 계좌별 주문 이력 커서 페이징 응답.
 * {@code GET /api/accounts/me/orders}의 응답 본문으로 사용됩니다.
 */
public record OrderPageResponse(
        List<OrderDetailResponse> items,
        String nextCursor,
        boolean hasNext
) {
}
