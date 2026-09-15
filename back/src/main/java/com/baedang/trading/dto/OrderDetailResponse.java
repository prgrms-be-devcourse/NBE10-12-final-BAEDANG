package com.baedang.trading.dto;

import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.MarketCountry;

import java.time.OffsetDateTime;

import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.currency;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;
import static com.baedang.global.formatter.FinancialDecimalFormatter.rate;

/** 원본 지정가와 확정 종목 통화 지정가를 구분하고 체결 금액은 누적으로 표시합니다. */
public record OrderDetailResponse(
        Long orderId,
        Long accountId,
        Long stockId,
        String symbol,
        String name,
        MarketCountry marketCountry,
        OrderType orderType,
        OrderSide side,
        OrderStatus status,
        String quantity,
        String filledQuantity,
        String activeRemainingQuantity,
        String requestedLimitPrice,
        String requestedLimitCurrency,
        String limitPrice,
        String acceptanceExchangeRate,
        String reservedCash,
        String grossAmount,
        String fee,
        String tax,
        String netAmount,
        String rejectReason,
        OffsetDateTime orderedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime closedAt
) {
    public static OrderDetailResponse from(TradeOrder o, Stock stock) {
        return new OrderDetailResponse(
                o.getOrderId(),
                o.getAccountId(),
                o.getStockId(),
                stock.getSymbol(),
                stock.getName(),
                stock.getMarketCountry(),
                o.getOrderType(),
                o.getSide(),
                o.getStatus(),
                plain(o.getQuantity()),
                plain(o.getFilledQuantity()),
                plain(o.activeRemainingQuantity()),
                currency(o.getRequestedLimitPrice(), o.getRequestedLimitCurrency()),
                o.getRequestedLimitCurrency(),
                currency(o.getLimitPrice(), stock.getMarketCountry().defaultCurrency()),
                rate(o.getAcceptanceExchangeRate()),
                krw(o.getReservedCash()),
                krw(o.getGrossAmount()),
                krw(o.getFee()),
                krw(o.getTax()),
                krw(o.getNetAmount()),
                o.getRejectReason(),
                o.getOrderedAt(),
                o.getExpiresAt(),
                o.getClosedAt()
        );
    }
}
