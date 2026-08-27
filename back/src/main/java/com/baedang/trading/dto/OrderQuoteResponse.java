package com.baedang.trading.dto;

import com.baedang.global.error.ErrorCode;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.OrderAmount;
import com.baedang.stock.entity.MarketCountry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.baedang.trading.model.AmountFormatter.plain;

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
                plain(amount.executedPrice()),
                plain(amount.exchangeRate()),
                plain(amount.grossAmount()),
                plain(amount.fee()),
                plain(amount.tax()),
                plain(amount.netAmount()),
                plain(availableCash),
                quoteAt,
                reason == null,
                reason == null ? null : reason.name()
        );
    }

}
