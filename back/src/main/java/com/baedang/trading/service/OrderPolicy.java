package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.stock.entity.ListingStatus;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.OrderInput;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.ClientOrderRetryPolicy;
import com.baedang.trading.model.OrderTerms;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 시장가·지정가 주문과 견적이 공유하는 입력·종목·시세·시장 컨텍스트 검증 정책입니다. */
@Component
public class OrderPolicy {

    private static final int MAX_QUANTITY_INPUT_LENGTH = 32;
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("\\d+(\\.0+)?");

    private final Duration quoteMaxStaleness;
    private final Duration executionContextMaxAge;
    private final BigDecimal maxOrderQuantity;

    public OrderPolicy(
            @Value("${trading.quote-max-staleness-seconds}") long quoteMaxStalenessSeconds,
            @Value("${trading.execution-context-max-age-seconds}") long executionContextMaxAgeSeconds,
            @Value("${trading.max-order-quantity}") BigDecimal maxOrderQuantity
    ) {
        this.quoteMaxStaleness = Duration.ofSeconds(quoteMaxStalenessSeconds);
        this.executionContextMaxAge = Duration.ofSeconds(executionContextMaxAgeSeconds);
        this.maxOrderQuantity = maxOrderQuantity;
    }

    public OrderInput parseInput(
            Long accountId,
            String clientOrderId,
            String symbol,
            String marketCountry,
            String side,
            String quantity
    ) {
        UUID parsedClientOrderId = parseClientOrderId(clientOrderId);
        Long parsedAccountId = parseAccountId(accountId);
        try {
            return new OrderInput(
                    parsedAccountId,
                    parsedClientOrderId,
                    parseTerms(symbol, marketCountry, side, quantity));
        } catch (BusinessException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            if (e.getData() != null) data.putAll(e.getData());
            data.putAll(ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
            if (e.getDetail() == null) throw new BusinessException(e.getErrorCode(), data);
            throw new BusinessException(e.getErrorCode(), e.getDetail(), data);
        }
    }

    private Long parseAccountId(Long accountId) {
        if (accountId == null || accountId <= 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    Map.of(
                            "field", "accountId",
                            "retryPolicy", ClientOrderRetryPolicy.NOT_RETRYABLE.name()));
        }
        return accountId;
    }

    public OrderTerms parseTerms(String symbol, String marketCountry, String side, String quantity) {
        return new OrderTerms(
                normalizeSymbol(symbol),
                parseMarketCountry(marketCountry),
                parseSide(side),
                parseQuantity(quantity));
    }

    public ErrorCode determineStaticRejection(Stock stock) {
        if (!Boolean.TRUE.equals(stock.getIsRanked()) || stock.getListingStatus() != ListingStatus.ACTIVE) {
            return ErrorCode.NOT_IN_UNIVERSE;
        }
        if (Boolean.TRUE.equals(stock.getIsSuspended())) return ErrorCode.STOCK_SUSPENDED;
        if (Boolean.TRUE.equals(stock.getIsLiquidation())) return ErrorCode.STOCK_LIQUIDATION;
        return null;
    }

    public void validateExecutionContextFresh(OrderMarketContext context, Instant now) {
        if (context == null || context.checkedAt() == null || now == null) {
            throw new BusinessException(ErrorCode.MARKET_CONTEXT_EXPIRED,
                    ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
        }
        Duration age = Duration.between(context.checkedAt(), now);
        if (age.isNegative() || age.compareTo(executionContextMaxAge) > 0) {
            throw new BusinessException(
                    ErrorCode.MARKET_CONTEXT_EXPIRED,
                    ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
        }
        // 컨텍스트 준비 시각과 환율의 원본 유효기간/수신 TTL은 서로 다릅니다.
        // 잠금 후 신규 주문만 검사하며 만료 시 외부 재조회 없이 같은 ID 재시도를 안내합니다.
        if (context.executionRateEvidence() == null
                || !context.executionRateEvidence().isValidAt(now.atOffset(ZoneOffset.UTC))
                || (context.marketCountry() == MarketCountry.KR
                    && context.executionRate().compareTo(BigDecimal.ONE) != 0)) {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND,
                    ClientOrderRetryPolicy.SAME_CLIENT_ORDER_ID.asData());
        }
    }

    public boolean hasValidCurrencyForMarket(Stock stock, QuoteSnapshot quote) {
        if (stock.getCurrency() == null || quote.getCurrency() == null) return false;

        String expectedCurrency = stock.getMarketCountry().defaultCurrency();
        return expectedCurrency.equalsIgnoreCase(stock.getCurrency().trim())
                && expectedCurrency.equalsIgnoreCase(quote.getCurrency().trim());
    }

    public ErrorCode validateQuoteTime(QuoteSnapshot quote, Instant now) {
        Duration age = Duration.between(quote.getQuoteAt().toInstant(), now);
        if (age.isNegative()) return ErrorCode.FUTURE_QUOTE;
        if (age.compareTo(quoteMaxStaleness) > 0) return ErrorCode.STALE_QUOTE;
        return null;
    }

    private UUID parseClientOrderId(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    Map.of(
                            "field", "clientOrderId",
                            "retryPolicy", ClientOrderRetryPolicy.NOT_RETRYABLE.name()));
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "clientOrderId=" + value,
                    Map.of("field", "clientOrderId", "retryPolicy", ClientOrderRetryPolicy.NOT_RETRYABLE.name()));
        }
    }

    private OrderSide parseSide(String value) {
        if (value == null || value.isBlank()) throw missingField("side");
        try {
            return OrderSide.valueOf(DomainNormalizer.upperCode(value));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "side=" + value, Map.of("field", "side"));
        }
    }

    private MarketCountry parseMarketCountry(String value) {
        if (value == null || value.isBlank()) throw missingField("marketCountry");
        return MarketCountry.parse(value)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "marketCountry=" + value, Map.of("field", "marketCountry")));
    }

    private BigDecimal parseQuantity(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY, Map.of("field", "quantity"));
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_QUANTITY_INPUT_LENGTH
                || !QUANTITY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY, "quantity=" + value, Map.of("field", "quantity"));
        }
        try {
            BigDecimal parsed = new BigDecimal(normalized);
            if (parsed.compareTo(BigDecimal.ONE) < 0
                    || parsed.compareTo(maxOrderQuantity) > 0
                    || parsed.stripTrailingZeros().scale() > 0) {
                throw new BusinessException(ErrorCode.INVALID_QUANTITY, "quantity=" + value, Map.of("field", "quantity"));
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY, "quantity=" + value, Map.of("field", "quantity"));
        }
    }

    private String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) throw missingField("symbol");
        return DomainNormalizer.symbol(value);
    }

    private BusinessException missingField(String field) {
        return new BusinessException(ErrorCode.INVALID_INPUT, Map.of("field", field));
    }
}
