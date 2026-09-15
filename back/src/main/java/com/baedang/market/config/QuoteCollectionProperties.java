package com.baedang.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("trading.quote-collection")
public record QuoteCollectionProperties(
        @DefaultValue("5s") Duration refreshInterval,
        @DefaultValue("20s") Duration requestTimeout,
        @DefaultValue("3") int backgroundConcurrency,
        @DefaultValue("1000") int maxInFlightStocks,
        @DefaultValue("8") int backgroundRequestsPerSecond) {
    public QuoteCollectionProperties {
        if (refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || backgroundConcurrency < 1 || backgroundConcurrency > 8 || maxInFlightStocks < 200
                || maxInFlightStocks > 10000 || backgroundRequestsPerSecond < 1 || backgroundRequestsPerSecond > 10) {
            throw new IllegalArgumentException("현재가 수집 설정이 허용 범위를 벗어났습니다");
        }
    }
}
