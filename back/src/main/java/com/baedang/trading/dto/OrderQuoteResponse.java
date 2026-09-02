package com.baedang.trading.dto;

import com.baedang.global.error.ErrorCode;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.OrderAmount;
import com.baedang.stock.entity.MarketCountry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.baedang.global.formatter.FinancialDecimalFormatter.currency;
import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;
import static com.baedang.global.formatter.FinancialDecimalFormatter.rate;

public record OrderQuoteResponse(
        String symbol,
        MarketCountry marketCountry,
        OrderSide side,
        String quantity,
        String executedPrice,
        String exchangeRate,
        String grossAmount,
        String fee,
        String tax,
        String netAmount,
        String availableCash,
        OffsetDateTime quoteAt,
        boolean executable,
        String reason
) {

    public static OrderQuoteResponse of(
            String symbol,
            MarketCountry marketCountry,
            OrderSide side,
            BigDecimal quantity,
            OrderAmount amount,
            BigDecimal availableCash,
            OffsetDateTime quoteAt,
            ErrorCode reason
    ) {
        return new OrderQuoteResponse(
                symbol,
                marketCountry,
                side,
                plain(quantity),
                currency(amount.executedPrice(), marketCountry.defaultCurrency()),
                rate(amount.exchangeRate()),
                krw(amount.grossAmount()),
                krw(amount.fee()),
                krw(amount.tax()),
                krw(amount.netAmount()),
                krw(availableCash),
                quoteAt,
                reason == null,
                reason == null ? null : reason.name()
        );
    }
}
