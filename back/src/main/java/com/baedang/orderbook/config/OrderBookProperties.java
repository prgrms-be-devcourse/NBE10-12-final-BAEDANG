package com.baedang.orderbook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "trading.orderbook")
public record OrderBookProperties(
        boolean enabled,
        String policyVersion,
        Duration refreshInterval,
        Duration maxQuoteAge,
        BigDecimal krBaseNotional,
        BigDecimal usBaseNotional,
        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        int noiseMinBps,
        int noiseMaxBps,
        Duration closedVersionRetention,
        Duration refreshInitialDelay,
        Duration retentionInitialDelay
) {
    public OrderBookProperties(
            boolean enabled,
            String policyVersion,
            Duration refreshInterval,
            Duration maxQuoteAge,
            BigDecimal krBaseNotional,
            BigDecimal usBaseNotional,
            BigDecimal minQuantity,
            BigDecimal maxQuantity,
            int noiseMinBps,
            int noiseMaxBps,
            Duration closedVersionRetention
    ) {
        this(enabled, policyVersion, refreshInterval, maxQuoteAge, krBaseNotional, usBaseNotional,
                minQuantity, maxQuantity, noiseMinBps, noiseMaxBps, closedVersionRetention,
                Duration.ZERO, Duration.ZERO);
    }
    @ConstructorBinding
    public OrderBookProperties {
        if (!StringUtils.hasText(policyVersion)) throw new IllegalArgumentException("orderbook policy-version은 필수입니다");
        if (refreshInitialDelay == null || refreshInitialDelay.isNegative()) throw new IllegalArgumentException("orderbook refresh-initial-delay는 0 이상이어야 합니다");
        if (retentionInitialDelay == null || retentionInitialDelay.isNegative()) throw new IllegalArgumentException("orderbook retention-initial-delay는 0 이상이어야 합니다");
        if (refreshInterval == null || refreshInterval.isZero() || refreshInterval.isNegative()) throw new IllegalArgumentException("orderbook refresh-interval은 양수여야 합니다");
        if (maxQuoteAge == null || maxQuoteAge.isZero() || maxQuoteAge.isNegative()) throw new IllegalArgumentException("orderbook max-quote-age는 양수여야 합니다");
        if (krBaseNotional == null || krBaseNotional.signum() <= 0) throw new IllegalArgumentException("orderbook kr-base-notional은 양수여야 합니다");
        if (usBaseNotional == null || usBaseNotional.signum() <= 0) throw new IllegalArgumentException("orderbook us-base-notional은 양수여야 합니다");
        if (minQuantity == null || minQuantity.compareTo(BigDecimal.ONE) < 0
                || minQuantity.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("orderbook min-quantity는 1 이상의 정수여야 합니다");
        if (maxQuantity == null || maxQuantity.compareTo(minQuantity) < 0
                || maxQuantity.stripTrailingZeros().scale() > 0
                || maxQuantity.compareTo(new BigDecimal("10000000000000")) >= 0) throw new IllegalArgumentException("orderbook max-quantity는 min 이상이며 NUMERIC(19,6)에 저장 가능한 정수여야 합니다");
        if (noiseMinBps <= 0 || noiseMaxBps < noiseMinBps) throw new IllegalArgumentException("orderbook noise 범위가 올바르지 않습니다");
        if (closedVersionRetention == null || closedVersionRetention.isZero() || closedVersionRetention.isNegative()) throw new IllegalArgumentException("orderbook closed-version-retention은 양수여야 합니다");
    }
}
