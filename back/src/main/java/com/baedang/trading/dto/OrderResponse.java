package com.baedang.trading.dto;

import com.baedang.trading.model.MarketOrderReceipt;
import com.baedang.stock.entity.MarketCountry;
import java.time.OffsetDateTime;

import static com.baedang.global.formatter.FinancialDecimalFormatter.currency;
import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;
import static com.baedang.global.formatter.FinancialDecimalFormatter.preserveScale;

public record OrderResponse(
        Long orderId,
        String status,
        String symbol,
        MarketCountry marketCountry,
        String side,
        String quantity,
        String executedPrice,
        String exchangeRate,
        String grossAmount,
        String fee,
        String tax,
        String netAmount,
        OffsetDateTime quoteAt,
        OffsetDateTime orderedAt,
        AccountSummary account
) {

    public static OrderResponse from(MarketOrderReceipt receipt) {
        return new OrderResponse(
                receipt.orderId(),
                receipt.status(),
                receipt.symbol(),
                receipt.marketCountry(),
                receipt.side(),
                plain(receipt.quantity()),
                currency(receipt.executedPrice(), currencyOf(receipt.marketCountry())),
                preserveScale(receipt.exchangeRate()),
                krw(receipt.grossAmount()),
                krw(receipt.fee()),
                krw(receipt.tax()),
                krw(receipt.netAmount()),
                receipt.quoteAt(),
                receipt.orderedAt(),
                new AccountSummary(krw(receipt.cashBalanceAfter()))
        );
    }

    private static String currencyOf(MarketCountry marketCountry) {
        return marketCountry == MarketCountry.KR ? "KRW" : "USD";
    }

    public record AccountSummary(String cashBalanceAfter) {
    }
}
