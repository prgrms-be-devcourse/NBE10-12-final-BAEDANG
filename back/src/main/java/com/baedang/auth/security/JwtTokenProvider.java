package com.baedang.auth.security;

import io.jsonwebtoken.JwtBuilder;
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
    /** 레거시 토큰 도구의 호환용 클레임. 운영 인증은 auth_session으로 검증합니다. */
    private static final String CLAIM_TOKEN_VERSION = "token_version";

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

    public String createAccessToken(Long userId) {
        Instant expiry = clock.instant().plus(properties.accessTtl());
        return createToken(userId, UUID.randomUUID(), 0, TYPE_ACCESS, expiry);
    }

    /**
     * 레거시 호출부·테스트용 — 버전을 신경 쓰지 않는 refresh token(버전 0)을 만듭니다.
     * 실제 로그인·가입 흐름은 {@link #createRefreshToken(Long, UUID, long, Instant)}을 씁니다.
     */
    public String createRefreshToken(Long userId) {
        return createRefreshToken(userId, 0);
    }

    /** 호환 도구용 토큰입니다. DB 세션을 생성하지 않으므로 운영 인증에는 사용할 수 없습니다. */
    public String createRefreshToken(Long userId, int tokenVersion) {
        Instant expiry = clock.instant().plus(properties.refreshTtl());
        return createToken(userId, UUID.randomUUID(), 0, TYPE_REFRESH, expiry, tokenVersion);
    }

    public String createRefreshToken(Long userId, UUID sessionId, long generation, Instant expiry) {
        return createToken(userId, sessionId, generation, TYPE_REFRESH, expiry);
    }

    public Identity accessIdentity(String token) { return identity(accessParser, token); }
    public Identity refreshIdentity(String token) { return identity(refreshParser, token); }

    public Long parseAccessToken(String token) { return accessIdentity(token).userId(); }
    public Long parseRefreshToken(String token) { return refreshIdentity(token).userId(); }

    /**
     * refresh token에 실린 {@code token_version}을 읽습니다. 클레임이 없으면(버전을
     * 신경 쓰지 않고 발급된 토큰) 0을 돌려줍니다.
     */
    public int parseRefreshTokenVersion(String token) {
        Object claim = refreshParser.parseSignedClaims(token).getPayload().get(CLAIM_TOKEN_VERSION);
        return claim == null ? 0 : ((Number) claim).intValue();
    }

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
        return createToken(userId, sessionId, generation, type, expiry, null);
    }

    private String createToken(Long userId, UUID sessionId, long generation, String type, Instant expiry, Integer tokenVersion) {
        if (userId == null || sessionId == null || generation < 0) {
            throw new IllegalArgumentException("JWT 발급 정보가 올바르지 않습니다");
        }
        JwtBuilder builder = Jwts.builder().issuer(properties.issuer()).subject(userId.toString())
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(expiry)).claim(CLAIM_TOKEN_TYPE, type)
                .claim("sid", sessionId.toString()).claim("generation", generation);
        if (tokenVersion != null) {
            builder.claim(CLAIM_TOKEN_VERSION, tokenVersion);
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }
}
