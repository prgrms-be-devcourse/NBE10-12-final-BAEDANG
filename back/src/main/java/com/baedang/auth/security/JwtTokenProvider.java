package com.baedang.auth.security;

import com.baedang.user.entity.User;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

@Component
public class JwtTokenProvider {
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final String CLAIM_TOKEN_TYPE = "token_type";
    /** 비밀번호 재설정 등으로 무효화된 refresh token을 가려내는 데 쓴다(User.tokenVersion 참고). */
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

    public String createAccessToken(Long userId) {
        return createToken(userId, TYPE_ACCESS, properties.accessTtl(), null);
    }

    /**
     * 레거시 호출부·테스트용 — 버전을 신경 쓰지 않는 refresh token(버전 0)을 만듭니다.
     * 실제 로그인·가입 흐름은 {@link #createRefreshToken(Long, int)}로 회원의 현재
     * {@code tokenVersion}을 실어야 합니다.
     */
    public String createRefreshToken(Long userId) {
        return createRefreshToken(userId, 0);
    }

    /** 발급 시점 회원의 {@code tokenVersion}을 실은 refresh token을 만듭니다. */
    public String createRefreshToken(Long userId, int tokenVersion) {
        return createToken(userId, TYPE_REFRESH, properties.refreshTtl(), tokenVersion);
    }

    public Long parseAccessToken(String token) {
        return parseSubject(accessParser, token);
    }

    public Long parseRefreshToken(String token) {
        return parseSubject(refreshParser, token);
    }

    /**
     * refresh token에 실린 {@code token_version}을 읽습니다. 클레임이 없으면(버전을
     * 신경 쓰지 않고 발급된 토큰) 0을 돌려줍니다 — {@link User#getTokenVersion()}의
     * 초기값과 같아 신규 회원의 토큰과 동일하게 취급됩니다.
     */
    public int parseRefreshTokenVersion(String token) {
        Object claim = refreshParser.parseSignedClaims(token).getPayload().get(CLAIM_TOKEN_VERSION);
        return claim == null ? 0 : ((Number) claim).intValue();
    }

    private String createToken(Long userId, String tokenType, Duration ttl, Integer tokenVersion) {
        if (userId == null) throw new IllegalArgumentException("userId는 필수입니다");

        Instant issuedAt = clock.instant();
        var builder = Jwts.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(ttl)))
                .claim(CLAIM_TOKEN_TYPE, tokenType);
        if (tokenVersion != null) {
            builder.claim(CLAIM_TOKEN_VERSION, tokenVersion);
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
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
