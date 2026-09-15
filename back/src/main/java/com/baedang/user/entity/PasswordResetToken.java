package com.baedang.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 비밀번호 찾기(이메일 재설정) 토큰. {@code password_reset_token} 테이블.
 *
 * <p>평문 토큰은 이 엔티티가 들고 있지 않습니다 — 발급 시 한 번만 만들어 이메일에
 * 싣고, DB에는 {@link #tokenHash}(SHA-256 hex)만 저장합니다. 비밀번호 해시와 같은
 * 이유입니다: 이 테이블이 통째로 유출돼도 실제 재설정 링크를 재구성할 수 없어야 합니다.
 *
 * <p>토큰은 발급 후 상태가 바뀌지 않는 값 객체에 가깝습니다 — 유일하게 바뀌는 건
 * {@link #markUsed}로 소비 표시하는 것뿐이라 setter 대신 의미가 분명한 메서드만 엽니다.
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "password_reset_token_id")
    private Long passwordResetTokenId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PasswordResetToken() {
    }

    private PasswordResetToken(Long userId, String tokenHash, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static PasswordResetToken issue(Long userId, String tokenHash, OffsetDateTime expiresAt) {
        return new PasswordResetToken(userId, tokenHash, expiresAt);
    }

    /** 이 토큰이 실제로 비밀번호 재설정에 쓰였음을 표시합니다. 이후 재사용은 거절됩니다. */
    public void markUsed(OffsetDateTime usedAt) {
        this.usedAt = usedAt;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(OffsetDateTime now) {
        return !now.isBefore(expiresAt);
    }

    public Long getPasswordResetTokenId() {
        return passwordResetTokenId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
