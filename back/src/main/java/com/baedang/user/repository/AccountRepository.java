package com.baedang.user.repository;

import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 워커는 주문에 저장된 회차를 잠그며 새 ACTIVE 계좌로 대체하지 않습니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountId = :accountId")
    Optional<Account> findForUpdate(@Param("accountId") Long accountId);

    /** 회원당 ACTIVE 계좌는 부분 유니크 인덱스로 하나만 존재합니다. */
    Optional<Account> findByUserIdAndStatus(Long userId, AccountStatus status);

    /** 주문 멱등 조회에서 요청 계좌의 소유자를 확인합니다. 종료된 회차도 조회합니다. */
    Optional<Account> findByAccountIdAndUserId(Long accountId, Long userId);

    /** 거래는 이 메서드로 계좌를 먼저 잠근 뒤 잔액을 검증·변경합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.userId = :userId and a.status = :status")
    Optional<Account> findByUserIdAndStatusForUpdate(
            @Param("userId") Long userId,
            @Param("status") AccountStatus status
    );

    /** 초기화 요청이 지정한 계좌를 소유자까지 확인하며 잠급니다. CLOSED 멱등 재요청도 조회합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountId = :accountId and a.userId = :userId")
    Optional<Account> findByAccountIdAndUserIdForUpdate(
            @Param("accountId") Long accountId,
            @Param("userId") Long userId
    );

    /** 다음 회차 번호를 구할 때 씁니다. */
    Optional<Account> findTopByUserIdOrderByRoundNoDesc(Long userId);

    /**
     * 리더보드 자격 계좌 = {@code opened_at + 4주}를 채운 ACTIVE 계좌(설계문서 §6.4).
     * 시드 포함 여부는 <b>코호트(배치) 시점</b>에 정한다 — {@code includeSeed=false}면 실유저만
     * 랭킹해 순위·퍼센타일이 자기일관하도록 한다(읽기서 숨기면 순위 구멍·모집단 불일치).
     */
    @Query("""
            select a from Account a, com.baedang.user.entity.User u
            where a.userId = u.userId
              and a.status = :status
              and a.openedAt <= :openedAtOrBefore
              and (:includeSeed = true or u.seed = false)
            """)
    List<Account> findLeaderboardEligible(
            @Param("status") AccountStatus status,
            @Param("openedAtOrBefore") OffsetDateTime openedAtOrBefore,
            @Param("includeSeed") boolean includeSeed
    );
}
