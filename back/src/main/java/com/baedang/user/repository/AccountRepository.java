package com.baedang.user.repository;

import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

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
}
