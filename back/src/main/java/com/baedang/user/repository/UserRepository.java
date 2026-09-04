package com.baedang.user.repository;

import com.baedang.user.entity.User;
import com.baedang.user.entity.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUserIdAndStatus(Long userId, UserStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.userId = :userId and u.status = :status")
    Optional<User> findByUserIdAndStatusForUpdate(
            @Param("userId") Long userId,
            @Param("status") UserStatus status
    );

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);
}
