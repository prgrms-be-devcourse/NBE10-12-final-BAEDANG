package com.baedang.trading.dto;

import com.baedang.trading.model.MarketOrderReceipt;
import com.baedang.stock.entity.MarketCountry;
import java.time.OffsetDateTime;

import static com.baedang.trading.model.AmountFormatter.plain;

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
                plain(receipt.executedPrice()),
                plain(receipt.exchangeRate()),
                plain(receipt.grossAmount()),
                plain(receipt.fee()),
                plain(receipt.tax()),
                plain(receipt.netAmount()),
                receipt.quoteAt(),
                receipt.orderedAt(),
                new AccountSummary(plain(receipt.cashBalanceAfter()))
        );
    }

    public record AccountSummary(String cashBalanceAfter) {
    }
}
