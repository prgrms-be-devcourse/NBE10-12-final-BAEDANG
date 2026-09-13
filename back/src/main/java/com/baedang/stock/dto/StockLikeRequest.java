package com.baedang.stock.dto;

import jakarta.validation.constraints.NotNull;

public record StockLikeRequest(
        @NotNull(message = "종목 ID는 필수입니다")
        Long stockId
) {
}
