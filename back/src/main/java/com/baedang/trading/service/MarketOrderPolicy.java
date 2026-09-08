package com.baedang.trading.service;

import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.MarketOrderAmount;
import com.baedang.user.entity.Account;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/** 시장가 즉시 체결의 순정산액·자원 검증과 거절 우선순위를 담당합니다. */
@Component
public class MarketOrderPolicy {
    private final OrderPolicy orderPolicy;

    public MarketOrderPolicy(OrderPolicy orderPolicy) {
        this.orderPolicy = orderPolicy;
    }

    public ErrorCode determineRejection(
            Account account,
            Stock stock,
            QuoteSnapshot quote,
            OrderSide side,
            BigDecimal quantity,
            MarketOrderAmount amount,
            BigDecimal availableQuantity,
            BooleanSupplier marketOpen,
            Instant now
    ) {
        ErrorCode staticRejection = orderPolicy.determineStaticRejection(stock);
        if (staticRejection != null) return staticRejection;
        if (!marketOpen.getAsBoolean()) return ErrorCode.MARKET_CLOSED;
        ErrorCode quoteTimeRejection = orderPolicy.validateQuoteTime(quote, now);
        if (quoteTimeRejection != null) return quoteTimeRejection;
        if (amount.netAmount().signum() <= 0) return ErrorCode.INVALID_SETTLEMENT_AMOUNT;
        if (side == OrderSide.BUY && account.availableCash().compareTo(amount.netAmount()) < 0) {
            return ErrorCode.INSUFFICIENT_CASH;
        }
        if (side == OrderSide.SELL && availableQuantity.compareTo(quantity) < 0) {
            return ErrorCode.INSUFFICIENT_QUANTITY;
        }
        return null;
    }

}
