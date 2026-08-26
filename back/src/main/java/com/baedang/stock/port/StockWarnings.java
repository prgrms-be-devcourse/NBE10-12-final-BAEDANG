package com.baedang.stock.port;

import java.time.LocalDate;
import java.util.List;

public record StockWarnings(
        String symbol,
        List<StockWarning> warnings
) {
    /**
     * 개별 유의사항.
     *
     * @param warningType OVERHEATED, INVESTMENT_WARNING, VI_STATIC 등.
     *                    미지정 코드 대비 원본 문자열 보존
     * @param exchange    KRX, NXT 등 거래소 코드. 거래소 무관 경고면 null
     */
    public record StockWarning(
        String warningType,
        String exchange,
        LocalDate startDate,
        LocalDate endDate
    ){
    }
}
