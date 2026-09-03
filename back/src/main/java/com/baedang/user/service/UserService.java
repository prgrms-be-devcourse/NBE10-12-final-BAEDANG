package com.baedang.user.service;

import com.baedang.auth.dto.UserResponse;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.user.dto.ChangePasswordRequest;
import com.baedang.user.dto.UpdateNicknameRequest;
import com.baedang.user.dto.WithdrawRequest;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.User;
import com.baedang.user.entity.UserStatus;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserRepository userRepository,
                       AccountRepository accountRepository,
                       PasswordEncoder passwordEncoder,
                       Clock clock) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(Long userId) {
        User user = userRepository.findByUserIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "userId=" + userId));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse changeNickname(Long userId, UpdateNicknameRequest request) {
        User user = userRepository.findByUserIdAndStatusForUpdate(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "userId=" + userId));

        if (user.getNickname().equals(request.nickname())) {
            return UserResponse.from(user);
        }

        if (userRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED, request.nickname());
        }

        user.changeNickname(request.nickname());
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED, request.nickname());
        }
        log.info("닉네임 변경 완료 userId={}", userId);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findByUserIdAndStatusForUpdate(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "userId=" + userId));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        log.info("비밀번호 변경 완료 userId={}", userId);
        return UserResponse.from(user);
    }

    @Transactional
    public void withdraw(Long userId, WithdrawRequest request) {
        User user = userRepository.findByUserIdAndStatusForUpdate(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "userId=" + userId));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        Account account = accountRepository.findByUserIdAndStatusForUpdate(userId, AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "userId=" + userId));

        OffsetDateTime withdrawnAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        user.withdraw();
        account.close(withdrawnAt);
        log.info("회원 탈퇴 완료 userId={} accountId={}", userId, account.getAccountId());
    }
}
