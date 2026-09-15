package com.baedang.stock.port;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StockInfo(
        String symbol,
        String name,
        String englishName,
        String isinCode,
        String market,
        String securityType,
        Boolean isCommonShare,
        String status,
        String currency,
        LocalDate listDate,
        LocalDate delistDate,
        BigDecimal sharesOutstanding,
        BigDecimal leverageFactor,
        KrMarketDetail krMarketDetail
) {
    /**
     * 국내 종목(KOSPI, KOSDAQ, KR_ETC)의 거래 제약 정보. 미국 종목은 null.
     */
    public record KrMarketDetail(
            boolean liquidationTrading,
            boolean nxtSupported,
            boolean krxTradingSuspended,
            Boolean nxtTradingSuspended)
    {
    }

}
