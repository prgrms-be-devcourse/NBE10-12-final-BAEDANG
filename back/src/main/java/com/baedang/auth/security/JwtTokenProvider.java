package com.baedang.auth.security;

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

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

        //jwt 전용 clock 사용
        io.jsonwebtoken.Clock jwtClock = () -> Date.from(clock.instant());

        this.accessParser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .require(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .clock(jwtClock)
                .build();

        this.refreshParser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .require(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
                .clock(jwtClock)
                .build();
    }

    public String createAccessToken(Long userId) {
        return createToken(userId, TYPE_ACCESS, properties.accessTtl());
    }

    public String createRefreshToken(Long userId) {
        return createToken(userId, TYPE_REFRESH, properties.refreshTtl());
    }

    public Long parseAccessToken(String token) {
        return parseSubject(accessParser, token);
    }

    public Long parseRefreshToken(String token) {
        return parseSubject(refreshParser, token);
    }

    private String createToken(Long userId, String tokenType, Duration ttl) {
        if (userId == null) throw new IllegalArgumentException("userId는 필수입니다");

        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(ttl)))
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private Long parseSubject(JwtParser parser, String token) {
        String subject = parser.parseSignedClaims(token).getPayload().getSubject();
        if (subject == null) throw new IllegalArgumentException("JWT subject가 없습니다");

        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JWT subject가 올바른 숫자 형식이 아닙니다: "+subject,e);
        }
    }
}
