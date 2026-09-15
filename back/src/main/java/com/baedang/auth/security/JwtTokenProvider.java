package com.baedang.auth.security;

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

@Component
public class JwtTokenProvider {
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final String CLAIM_TOKEN_TYPE = "token_type";

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;
    private final JwtParser accessParser;
    private final JwtParser refreshParser;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;

        try {
            byte[] keyBytes = Decoders.BASE64.decode(properties.secret());
            if (keyBytes.length < 32) {
                throw new IllegalArgumentException("JWT 시크릿 키는 최소 256비트(32바이트) 이상이어야 합니다.");
            }
            this.key = Keys.hmacShaKeyFor(keyBytes);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("올바르지 않는 Base64 JWT 시크릿 키입니다.", e);
        }

        this.accessParser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .require(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .clock(() -> Date.from(clock.instant()))
                .build();

        this.refreshParser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .require(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public record Identity(Long userId, UUID sessionId, long generation) {}

    public Instant sessionExpiresAt() {
        return clock.instant().plus(properties.refreshTtl());
    }

    public String createAccessToken(Long userId, UUID sessionId, Instant sessionExpiry) {
        Instant expiry = clock.instant().plus(properties.accessTtl());
        return createToken(userId, sessionId, 0, TYPE_ACCESS,
                expiry.isBefore(sessionExpiry) ? expiry : sessionExpiry);
    }

    public String createRefreshToken(Long userId, UUID sessionId, long generation, Instant expiry) {
        return createToken(userId, sessionId, generation, TYPE_REFRESH, expiry);
    }

    public Identity accessIdentity(String token) { return identity(accessParser, token); }
    public Identity refreshIdentity(String token) { return identity(refreshParser, token); }

    public Long parseAccessToken(String token) { return accessIdentity(token).userId(); }
    public Long parseRefreshToken(String token) { return refreshIdentity(token).userId(); }

    private Identity identity(JwtParser parser, String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();
        Long userId = Long.valueOf(claims.getSubject());
        String sessionId = claims.get("sid", String.class);
        if (sessionId == null) throw new IllegalArgumentException("JWT 세션 정보가 없습니다");
        UUID sid = UUID.fromString(sessionId);
        Number generation = claims.get("generation", Number.class);
        if (userId <= 0 || generation == null || generation.longValue() < 0
                || claims.getId() == null || claims.getExpiration() == null) {
            throw new IllegalArgumentException("JWT 필수 정보가 올바르지 않습니다");
        }
        return new Identity(userId, sid, generation.longValue());
    }

    private String createToken(Long userId, UUID sessionId, long generation, String type, Instant expiry) {
        if (userId == null || sessionId == null || generation < 0) {
            throw new IllegalArgumentException("JWT 발급 정보가 올바르지 않습니다");
        }
        return Jwts.builder().issuer(properties.issuer()).subject(userId.toString())
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(expiry)).claim(CLAIM_TOKEN_TYPE, type)
                .claim("sid", sessionId.toString()).claim("generation", generation)
                .signWith(key, Jwts.SIG.HS256).compact();
    }
}
