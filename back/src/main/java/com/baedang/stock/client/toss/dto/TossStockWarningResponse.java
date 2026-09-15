package com.baedang.stock.client.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;
/**
 * Toss GET /api/v1/stocks/{symbol}/warnings 원본 응답 DTO.
 *
 * 경고가 없으면 result가 빈 배열이다 — 빈 배열은 에러가 아니다.
 * warningType은 Toss에 미지정 코드가 추가될 수 있어 String으로 보존한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossStockWarningResponse(
        List<TossWarningItem> result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TossWarningItem(
            String warningType,
            String exchange,
            LocalDate startDate,
            LocalDate endDate
    ){

    }
}
