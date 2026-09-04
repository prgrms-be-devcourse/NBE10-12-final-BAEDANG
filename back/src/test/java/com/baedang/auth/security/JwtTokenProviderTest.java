package com.baedang.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {
    private static final String TEST_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String OTHER_SECRET = "OTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTBmZWRjYmE=";
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtProperties properties = new JwtProperties(
            "baedang",
            TEST_SECRET,
            Duration.ofMinutes(15),
            Duration.ofDays(7)
    );

    private final JwtTokenProvider provider = new JwtTokenProvider(properties, clock);

    @Test
    @DisplayName("access_token에 userId와 access_type, 15분 만료를 담는다")
    void t1() {
        Long userId = 7L;

        String token = provider.createAccessToken(userId);
        Long parsedUserId = provider.parseAccessToken(token);

        assertThat(parsedUserId).isEqualTo(userId);
    }

    @Test
    @DisplayName("refresh_token에 userId와 refresh_type, 7일 만료를 담는다")
    void t2() {
        Long userId = 7L;

        String token = provider.createRefreshToken(userId);
        Long parsedUserId = provider.parseRefreshToken(token);

        assertThat(parsedUserId).isEqualTo(userId);
    }

    @Test
    @DisplayName("만료된 access_token을 거절한다")
    void t3() {
        String token = provider.createAccessToken(7L);
        Clock futureClock = Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        JwtTokenProvider futureProvider = new JwtTokenProvider(properties, futureClock);

        assertThatThrownBy(() -> futureProvider.parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("다른 key로 서명된 token을 거절한다")
    void t4() {
        JwtProperties otherProps = new JwtProperties(
                "baedang",
                OTHER_SECRET,
                Duration.ofMinutes(15),
                Duration.ofDays(7)
        );

        JwtTokenProvider otherProvider = new JwtTokenProvider(otherProps, clock);
        String otherToken = otherProvider.createAccessToken(7L);

        assertThatThrownBy(() -> provider.parseAccessToken(otherToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("refresh_token을 access로 검증하면 거절한다")
    void refresh_token을_access로_검증하면_거절한다() {
        String refreshToken = provider.createRefreshToken(7L);

        assertThatThrownBy(() -> provider.parseAccessToken(refreshToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("access_token을 refresh로 검증하면 거절한다")
    void access_token을_refresh로_검증하면_거절한다() {
        String accessToken = provider.createAccessToken(7L);

        assertThatThrownBy(() -> provider.parseRefreshToken(accessToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("숫자가 아닌 subject를 거절한다")
    void 숫자가_아닌_subject를_거절한다() {
        byte[] keyBytes = io.jsonwebtoken.io.Decoders.BASE64.decode(TEST_SECRET);
        String invalidSubToken = Jwts.builder()
                .issuer("baedang")
                .subject("not-a-number")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plus(Duration.ofMinutes(15))))
                .claim("token_type", "access")
                .signWith(Keys.hmacShaKeyFor(keyBytes), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> provider.parseAccessToken(invalidSubToken))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("256bit보다 짧은 key를 거절한다")
    void 짧은_key를_거절한다() {
        String shortSecret = "MDEyMzQ1Njc4OWFiY2RlZg==";
        JwtProperties shortProps = new JwtProperties("baedang", shortSecret, Duration.ofMinutes(15), Duration.ofDays(7));

        assertThatThrownBy(() -> new JwtTokenProvider(shortProps, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잘못된 Base64 secret을 거절한다")
    void 잘못된_Base64_secret을_거절한다() {
        JwtProperties invalidProps = new JwtProperties("baedang", "invalid-base64!@#$", Duration.ofMinutes(15), Duration.ofDays(7));

        assertThatThrownBy(() -> new JwtTokenProvider(invalidProps, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
