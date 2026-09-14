package com.baedang.stock.dto;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockCategory;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record StockDetailResponse(
        String symbol,
        String name,
        String englishName,
        String market,
        MarketCountry marketCountry,
        String currency,
        String isinCode,
        StockCategory category,
        String leverageFactor,
        Boolean isDividend,
        Price price,
        Info info,
        List<Warning> warnings,
        WarningStatus warningsStatus,
        boolean tradable,
        String tradableReason
) {

    /**
     * 유의사항 조회 성공 여부. {@code UNAVAILABLE}은 "유의사항 없음"이 아니라
     * "확인하지 못했다"는 뜻이다 — 화면이 배지를 조용히 감추지 않도록 구분한다.
     */
    public enum WarningStatus {
        AVAILABLE,
        UNAVAILABLE
    }

    public record Price(
            String lastPrice,
            String prevClose,
            String changeAmount,
            String changeRate,
            String upperLimit,
            String lowerLimit,
            OffsetDateTime quoteAt,
            boolean realtime
    ) {
    }

    public record Info(
            String marketCap,
            String sharesOutstanding,
            LocalDate listDate
    ) {
    }

    public record Warning(String type, String label) {
    }
}
