package com.baedang.user.repository;

import com.baedang.user.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /**
     * 재설정 메일 요청 쿨다운 검사용 — 그 회원에게 가장 최근 발급된 토큰(사용·만료
     * 여부 무관)의 발급 시각을 확인해, 너무 짧은 간격의 재요청을 걸러낸다
     * (AuthService.requestPasswordReset 참고).
     */
    Optional<PasswordResetToken> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * 새 재설정 메일을 보내기 직전에 호출합니다 — 그 회원의 이전 미사용 토큰을 전부
     * 소비 처리해서, 받은편지함에 남아있는 오래된 링크가 계속 유효하지 않게 합니다.
     */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int invalidateUnusedByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);
}
