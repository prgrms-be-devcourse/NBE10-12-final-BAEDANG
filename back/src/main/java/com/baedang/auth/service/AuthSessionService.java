package com.baedang.auth.service;

import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.auth.security.JwtTokenProvider.Identity;
import com.baedang.auth.security.RefreshTokenCipher;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.user.entity.UserStatus;
import com.baedang.user.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AuthSessionService {
    public record Tokens(String accessToken, String refreshToken, Instant expiresAt) {}
    private record Session(UUID id, Long userId, String hash, long generation, String previousHash,
                           Instant graceUntil, String encrypted, Instant expiry, Instant revoked) {}
    private record Rotation(Tokens tokens, ErrorCode error) {}

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final JwtTokenProvider jwt;
    private final RefreshTokenCipher cipher;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Duration grace;

    public AuthSessionService(JdbcTemplate jdbc, UserRepository users, JwtTokenProvider jwt,
                              RefreshTokenCipher cipher, Clock clock, TransactionTemplate transactions,
                              @Value("${auth.session.rotation-grace:5s}") Duration grace) {
        if (grace.isNegative() || grace.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Refresh 유예는 0~30초여야 합니다");
        }
        this.jdbc = jdbc; this.users = users; this.jwt = jwt; this.cipher = cipher;
        this.clock = clock; this.transactions = transactions; this.grace = grace;
    }

    /** 가입 또는 잠긴 사용자 검증과 같은 트랜잭션에서만 새 세션을 만듭니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Tokens create(Long userId) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiry = jwt.sessionExpiresAt();
        String refresh = jwt.createRefreshToken(userId, id, 0, expiry);
        jdbc.update("INSERT INTO auth_session(id,user_id,refresh_token_hash,refresh_generation,created_at,expires_at) VALUES (?,?,?,0,?,?)",
                id, userId, hash(refresh), utc(now), utc(expiry));
        return tokens(userId, id, refresh, expiry);
    }

    @Transactional(propagation = Propagation.NEVER)
    public Tokens rotate(String token) {
        Identity identity = parseRefresh(token);
        Rotation result = transactions.execute(status -> rotateLocked(identity, token));
        // 폐기는 먼저 커밋되어야 하므로 트랜잭션 바깥에서 오류로 변환합니다.
        if (result == null) throw new IllegalStateException("세션 갱신 결과 누락");
        if (result.error() != null) throw new BusinessException(result.error());
        return result.tokens();
    }

    private Rotation rotateLocked(Identity identity, String token) {
        if (users.findByUserIdAndStatusForUpdate(identity.userId(), UserStatus.ACTIVE).isEmpty()) {
            return new Rotation(null, ErrorCode.INVALID_TOKEN);
        }
        List<Session> rows = jdbc.query("SELECT * FROM auth_session WHERE id=? AND user_id=? FOR UPDATE",
                this::read, identity.sessionId(), identity.userId());
        Instant now = clock.instant();
        if (rows.isEmpty()) return new Rotation(null, ErrorCode.INVALID_TOKEN);
        Session session = rows.getFirst();
        if (session.revoked() != null) return new Rotation(null, ErrorCode.SESSION_REVOKED);
        if (!now.isBefore(session.expiry())) return new Rotation(null, ErrorCode.TOKEN_EXPIRED);
        String submitted = hash(token);
        if (identity.generation() == session.generation() && matches(session.hash(), submitted)) {
            String next = jwt.createRefreshToken(identity.userId(), session.id(), session.generation() + 1, session.expiry());
            jdbc.update("UPDATE auth_session SET previous_token_hash=refresh_token_hash,refresh_token_hash=?,refresh_generation=refresh_generation+1,grace_until=?,encrypted_refresh=? WHERE id=?",
                    hash(next), utc(now.plus(grace)), cipher.encrypt(session.id(), next), session.id());
            return new Rotation(tokens(identity.userId(), session.id(), next, session.expiry()), null);
        }
        if (identity.generation() == session.generation() - 1 && matches(session.previousHash(), submitted)
                && session.graceUntil() != null && now.isBefore(session.graceUntil())) {
            return new Rotation(tokens(identity.userId(), session.id(), cipher.decrypt(session.id(), session.encrypted()), session.expiry()), null);
        }
        revoke(session.id(), now);
        return new Rotation(null, ErrorCode.REFRESH_TOKEN_REUSED);
    }

    @Transactional(readOnly = true)
    public void requireActive(Identity identity) {
        Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM auth_session s JOIN users u ON u.user_id=s.user_id WHERE s.id=? AND s.user_id=? AND s.revoked_at IS NULL AND s.expires_at>? AND u.status='ACTIVE')",
                Boolean.class, identity.sessionId(), identity.userId(), utc(clock.instant()));
        if (!Boolean.TRUE.equals(active)) throw new BusinessException(ErrorCode.SESSION_REVOKED);
    }

    @Transactional
    public void logout(String token) {
        Identity identity = parseRefresh(token);
        // 비밀번호 변경·탈퇴·회전과 같은 사용자 → 세션 잠금 순서를 지킵니다.
        if (users.findByUserIdAndStatusForUpdate(identity.userId(), UserStatus.ACTIVE).isPresent()) {
            jdbc.update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,?),encrypted_refresh=NULL,previous_token_hash=NULL,grace_until=NULL WHERE id=? AND user_id=?",
                    utc(clock.instant()), identity.sessionId(), identity.userId());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAll(Long userId) {
        jdbc.update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,?),encrypted_refresh=NULL,previous_token_hash=NULL,grace_until=NULL WHERE user_id=?",
                utc(clock.instant()), userId);
    }

    @Scheduled(fixedDelayString = "${auth.session.cleanup-delay:1h}", initialDelayString = "${auth.session.cleanup-delay:1h}")
    @Transactional
    public void cleanup() {
        Instant now = clock.instant();
        jdbc.update("UPDATE auth_session SET encrypted_refresh=NULL,previous_token_hash=NULL,grace_until=NULL WHERE grace_until<=?", utc(now));
        jdbc.update("DELETE FROM auth_session WHERE expires_at<?", utc(now.minus(Duration.ofDays(7))));
    }

    private void revoke(UUID id, Instant now) {
        jdbc.update("UPDATE auth_session SET revoked_at=?,encrypted_refresh=NULL,previous_token_hash=NULL,grace_until=NULL WHERE id=?", utc(now), id);
    }

    private Tokens tokens(Long userId, UUID id, String refresh, Instant expiry) {
        return new Tokens(jwt.createAccessToken(userId, id, expiry), refresh, expiry);
    }

    private Identity parseRefresh(String token) {
        try { return jwt.refreshIdentity(token); }
        catch (ExpiredJwtException exception) { throw new BusinessException(ErrorCode.TOKEN_EXPIRED); }
        catch (JwtException | IllegalArgumentException exception) { throw new BusinessException(ErrorCode.INVALID_TOKEN); }
    }

    private Session read(ResultSet row, int index) throws SQLException {
        return new Session(row.getObject("id", UUID.class), row.getLong("user_id"), row.getString("refresh_token_hash"),
                row.getLong("refresh_generation"), row.getString("previous_token_hash"), instant(row, "grace_until"),
                row.getString("encrypted_refresh"), instant(row, "expires_at"), instant(row, "revoked_at"));
    }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static boolean matches(String expected, String actual) {
        return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
