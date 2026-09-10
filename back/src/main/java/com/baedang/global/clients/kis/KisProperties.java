package com.baedang.global.clients.kis;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "kis")
public record KisProperties(
        boolean enabled,
        URI baseUrl,
        String appKey,
        String appSecret,
        int requestsPerSecond,
        Duration connectTimeout,
        Duration readTimeout,
        Duration financialCacheTtl,
        Duration industryCacheTtl,
        boolean loadFinancials
) {

    public KisProperties {
        if (baseUrl == null) {
            throw new IllegalArgumentException("KIS base URL은 필수입니다");
        }
        if (requestsPerSecond < 1 || requestsPerSecond > 18) {
            throw new IllegalArgumentException("KIS requests-per-second는 1~18이어야 합니다");
        }
        requirePositive(connectTimeout, "KIS connect timeout");
        requirePositive(readTimeout, "KIS read timeout");
        requirePositive(financialCacheTtl, "KIS financial cache TTL");
        requirePositive(industryCacheTtl, "KIS industry cache TTL");
        if (enabled && !StringUtils.hasText(appKey)) {
            throw new IllegalArgumentException("KIS app key는 필수입니다");
        }
        if (enabled && !StringUtils.hasText(appSecret)) {
            throw new IllegalArgumentException("KIS app secret은 필수입니다");
        }
        if (!enabled && loadFinancials) {
            throw new IllegalArgumentException("KIS가 비활성화되면 재무정보 적재를 켤 수 없습니다");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "은 양수여야 합니다");
        }
    }
}
