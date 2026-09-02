package com.baedang.user.repository;

import com.baedang.user.entity.User;
import com.baedang.user.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUserIdAndStatus(Long userId, UserStatus status);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);
}
