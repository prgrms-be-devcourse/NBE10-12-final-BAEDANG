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

    /** 거래는 이 메서드로 계좌를 먼저 잠근 뒤 잔액을 검증·변경합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.userId = :userId and a.status = :status")
    Optional<Account> findByUserIdAndStatusForUpdate(
            @Param("userId") Long userId,
            @Param("status") AccountStatus status
    );

    /** 다음 회차 번호를 구할 때 씁니다. */
    Optional<Account> findTopByUserIdOrderByRoundNoDesc(Long userId);
}
