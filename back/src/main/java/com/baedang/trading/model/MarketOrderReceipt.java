package com.baedang.trading.model;

import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.TradeOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 거래 트랜잭션이 확정한 값만 담아 응답 계층으로 전달하는 불변 체결 영수증입니다. */
public record MarketOrderReceipt(
        Long orderId,
        String status,
        String symbol,
        MarketCountry marketCountry,
        String side,
        BigDecimal quantity,
        BigDecimal executedPrice,
        BigDecimal exchangeRate,
        BigDecimal grossAmount,
        BigDecimal fee,
        BigDecimal tax,
        BigDecimal netAmount,
        OffsetDateTime quoteAt,
        OffsetDateTime orderedAt,
        BigDecimal cashBalanceAfter
) {

    public static MarketOrderReceipt from(TradeOrder order, Stock stock, BigDecimal cashBalance) {
        return new MarketOrderReceipt(
                order.getOrderId(),
                order.getStatus().name(),
                stock.getSymbol(),
                stock.getMarketCountry(),
                order.getSide().name(),
                order.getQuantity(),
                order.getExecutedPrice(),
                order.getExchangeRate(),
                order.getGrossAmount(),
                order.getFee(),
                order.getTax(),
                order.getNetAmount(),
                order.getQuoteAt(),
                order.getOrderedAt(),
                cashBalance
        );
    }
}
