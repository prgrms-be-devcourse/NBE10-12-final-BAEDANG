package com.baedang.trading.dto;

import com.baedang.stock.entity.Stock;
import com.baedang.trading.entity.TradeOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record OrderResponse(
        Long orderId,
        String status,
        String symbol,
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

    public static OrderResponse filled(
            TradeOrder order,
            Stock stock,
            BigDecimal cashBalance,
            BigDecimal totalAsset
    ) {
        return new OrderResponse(
                order.getOrderId(),
                order.getStatus().name(),
                stock.getSymbol(),
                order.getSide().name(),
                plain(order.getQuantity()),
                plain(order.getExecutedPrice()),
                plain(order.getExchangeRate()),
                plain(order.getGrossAmount()),
                plain(order.getFee()),
                plain(order.getTax()),
                plain(order.getNetAmount()),
                order.getQuoteAt(),
                order.getOrderedAt(),
                new AccountSummary(plain(cashBalance), plain(totalAsset))
        );
    }

    private static String plain(BigDecimal value) {
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    public record AccountSummary(String cashBalance, String totalAsset) {
    }
}
