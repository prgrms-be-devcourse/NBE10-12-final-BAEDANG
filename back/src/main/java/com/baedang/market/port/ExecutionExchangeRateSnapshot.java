package com.baedang.market.port;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;

import static com.baedang.trading.support.NumericBounds.RATE_LIMIT;

/** 체결용 USD/KRW 환율. 원본 유효기간과 수신 완료 후 TTL을 모두 만족해야 합니다. */
public record ExecutionExchangeRateSnapshot(BigDecimal rate, OffsetDateTime fetchedAt,
                                            OffsetDateTime validFrom, OffsetDateTime validUntil) {
    public static final Duration MAX_AGE = Duration.ofSeconds(60);

    public ExecutionExchangeRateSnapshot {
        if (rate == null || rate.signum() <= 0 || rate.stripTrailingZeros().scale() > 6
                || rate.compareTo(RATE_LIMIT) >= 0
                || fetchedAt == null || validFrom == null || validUntil == null
                || !validFrom.isBefore(validUntil) || fetchedAt.isBefore(validFrom)
                || !fetchedAt.isBefore(validUntil)) {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        }
    }

    public static ExecutionExchangeRateSnapshot from(ExchangeRateQuote quote, OffsetDateTime fetchedAt) {
        if (quote == null || !"USD".equals(DomainNormalizer.currency(quote.baseCurrency()))
                || !"KRW".equals(DomainNormalizer.currency(quote.quoteCurrency()))) {
            throw new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        }
        return new ExecutionExchangeRateSnapshot(quote.rate(), fetchedAt, quote.validFrom(), quote.validUntil());
    }

    public boolean isValidAt(OffsetDateTime at) {
        return at != null && !at.isBefore(fetchedAt) && !at.isBefore(validFrom)
                && at.isBefore(validUntil) && at.isBefore(fetchedAt.plus(MAX_AGE));
    }
}
