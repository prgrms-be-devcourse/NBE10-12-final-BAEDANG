package com.baedang.market.event.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "krx.market-events")
public record KrxMarketEventProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://kind.krx.co.kr") URI baseUrl,
        @DefaultValue("15s") Duration pollInterval,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout
) {
    public KrxMarketEventProperties {
        if (baseUrl == null || !"https".equalsIgnoreCase(baseUrl.getScheme())
                || baseUrl.getHost() == null
                || baseUrl.getRawQuery() != null
                || (baseUrl.getRawPath() != null && !baseUrl.getRawPath().isBlank() && !"/".equals(baseUrl.getRawPath()))
                || baseUrl.getUserInfo() != null) {
            throw new IllegalArgumentException("KRX market-events base URL이 올바르지 않습니다");
        }
        requirePositive(pollInterval, "poll interval");
        requirePositive(connectTimeout, "connect timeout");
        requirePositive(readTimeout, "read timeout");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + "은(는) 0보다 커야 합니다");
        }
    }
}
