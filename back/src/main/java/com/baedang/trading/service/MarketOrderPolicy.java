package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.stock.entity.ListingStatus;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.OrderAmount;
import com.baedang.trading.model.OrderTerms;
import com.baedang.user.entity.Account;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** 견적 조회와 실제 주문이 동일한 입력·거래 가능 규칙을 사용하도록 모은 정책입니다. */
@Component
public class MarketOrderPolicy {

    private static final int MAX_QUANTITY_INPUT_LENGTH = 32;
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("\\d+(\\.0+)?");

    private final Duration quoteMaxStaleness;
    private final BigDecimal maxOrderQuantity;

    public MarketOrderPolicy(
            @Value("${trading.quote-max-staleness-seconds}") long quoteMaxStalenessSeconds,
            @Value("${trading.max-order-quantity}") BigDecimal maxOrderQuantity
    ) {
        this.quoteMaxStaleness = Duration.ofSeconds(quoteMaxStalenessSeconds);
        this.maxOrderQuantity = maxOrderQuantity;
    }

    public MarketOrderCommand parseCommand(
            String clientOrderId,
            String symbol,
            String side,
            String quantity
    ) {
        return new MarketOrderCommand(parseClientOrderId(clientOrderId), parseTerms(symbol, side, quantity));
    }

    public OrderTerms parseTerms(String symbol, String side, String quantity) {
        return new OrderTerms(normalizeSymbol(symbol), parseSide(side), parseQuantity(quantity));
    }

    public ErrorCode determineRejection(
            Account account,
            Stock stock,
            QuoteSnapshot quote,
            OrderSide side,
            BigDecimal quantity,
            OrderAmount amount,
            BigDecimal availableQuantity,
            BooleanSupplier marketOpen,
            Instant now
    ) {
        if (!Boolean.TRUE.equals(stock.getIsRanked()) || stock.getListingStatus() != ListingStatus.ACTIVE) {
            return ErrorCode.NOT_IN_UNIVERSE;
        }
        if (Boolean.TRUE.equals(stock.getIsSuspended())) return ErrorCode.STOCK_SUSPENDED;
        if (Boolean.TRUE.equals(stock.getIsLiquidation())) return ErrorCode.STOCK_LIQUIDATION;
        if (!marketOpen.getAsBoolean()) return ErrorCode.MARKET_CLOSED;
        ErrorCode quoteTimeRejection = validateQuoteTime(quote, now);
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

    private ErrorCode validateQuoteTime(QuoteSnapshot quote, Instant now) {
        Duration age = Duration.between(quote.getQuoteAt().toInstant(), now);
        if (age.isNegative()) return ErrorCode.FUTURE_QUOTE;
        if (age.compareTo(quoteMaxStaleness) > 0) return ErrorCode.STALE_QUOTE;
        return null;
    }

    private UUID parseClientOrderId(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.INVALID_INPUT);
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "clientOrderId=" + value);
        }
    }

    private OrderSide parseSide(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.INVALID_INPUT);
        try {
            return OrderSide.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "side=" + value);
        }
    }

    private BigDecimal parseQuantity(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        String normalized = value.trim();
        if (normalized.length() > MAX_QUANTITY_INPUT_LENGTH
                || !QUANTITY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY, "quantity=" + value);
        }
        try {
            BigDecimal parsed = new BigDecimal(normalized);
            if (parsed.compareTo(BigDecimal.ONE) < 0
                    || parsed.compareTo(maxOrderQuantity) > 0
                    || parsed.stripTrailingZeros().scale() > 0) {
                throw new BusinessException(ErrorCode.INVALID_QUANTITY, "quantity=" + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY, "quantity=" + value);
        }
    }

    private String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
