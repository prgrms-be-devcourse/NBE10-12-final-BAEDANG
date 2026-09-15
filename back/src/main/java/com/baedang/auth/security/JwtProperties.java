package com.baedang.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        String issuer,
        String secret,
        Duration accessTtl,
        Duration refreshTtl
) {
    public JwtProperties {
        if (!StringUtils.hasText(issuer)) throw new IllegalArgumentException("JWT issuer는 필수입니다");
        if (!StringUtils.hasText(secret)) throw new IllegalArgumentException("JWT secret은 필수입니다");

        if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()) {
            throw new IllegalArgumentException("JWT access TTL은 양수여야 합니다");
        }
        if (refreshTtl == null || refreshTtl.isZero() || refreshTtl.isNegative()) {
            throw new IllegalArgumentException("JWT refresh TTL은 양수여야합니다");
        }
    }
}
