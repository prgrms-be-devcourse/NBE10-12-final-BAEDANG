package com.baedang.user.repository;

import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 회원당 ACTIVE 계좌는 부분 유니크 인덱스로 하나만 존재합니다. */
    Optional<Account> findByUserIdAndStatus(Long userId, AccountStatus status);

    /** 다음 회차 번호를 구할 때 씁니다. */
    Optional<Account> findTopByUserIdOrderByRoundNoDesc(Long userId);
}
