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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserRepository userRepository;
    private AccountRepository accountRepository;
    private PasswordEncoder passwordEncoder;
    private Clock clock;
    private UserService userService;

    private final Instant now = Instant.parse("2026-09-02T00:00:00Z");

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        accountRepository = mock(AccountRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        clock = Clock.fixed(now, ZoneOffset.UTC);

        userService = new UserService(
                userRepository,
                accountRepository,
                passwordEncoder,
                clock
        );
    }

    @Test
    @DisplayName("ACTIVE 회원을 정상 조회한다")
    void ACTIVE_user를_조회한다() {
        User user = User.create("test@example.com", "hash", "테스터");
        ReflectionTestUtils.setField(user, "userId", 7L);

        when(userRepository.findByUserIdAndStatus(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));

        UserResponse response = userService.getMe(7L);

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.nickname()).isEqualTo("테스터");
    }

    @Test
    @DisplayName("회원이 없거나 WITHDRAWN 상태면 USER_NOT_FOUND 예외가 발생한다")
    void 없는_user_조회는_USER_NOT_FOUND다() {
        when(userRepository.findByUserIdAndStatus(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getMe(7L))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원이 사용 중인 닉네임으로 변경 시 NICKNAME_DUPLICATED 예외가 발생한다")
    void 다른_user가_사용중인_nickname은_NICKNAME_DUPLICATED다() {
        User user = User.create("test@example.com", "hash", "기존닉");
        ReflectionTestUtils.setField(user, "userId", 7L);

        when(userRepository.findByUserIdAndStatusForUpdate(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("중복닉")).thenReturn(true);

        assertThatThrownBy(() -> userService.changeNickname(7L, new UpdateNicknameRequest("중복닉")))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.NICKNAME_DUPLICATED);

        assertThat(user.getNickname()).isEqualTo("기존닉");
    }

    @Test
    @DisplayName("현재 닉네임과 같은 닉네임으로 변경 시 중복 검사 없이 현재 정보를 반환한다")
    void 같은_nickname은_변경없이_현재응답을_반환한다() {
        User user = User.create("test@example.com", "hash", "동일닉");
        ReflectionTestUtils.setField(user, "userId", 7L);

        when(userRepository.findByUserIdAndStatusForUpdate(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));

        UserResponse response = userService.changeNickname(7L, new UpdateNicknameRequest("동일닉"));

        assertThat(response.nickname()).isEqualTo("동일닉");
        verify(userRepository, never()).existsByNickname(any());
    }

    @Test
    @DisplayName("새 닉네임으로 정상 변경된다")
    void 새_nickname으로_정상_변경한다() {
        User user = User.create("test@example.com", "hash", "기존닉");
        ReflectionTestUtils.setField(user, "userId", 7L);

        when(userRepository.findByUserIdAndStatusForUpdate(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("새닉네임")).thenReturn(false);

        UserResponse response = userService.changeNickname(7L, new UpdateNicknameRequest("새닉네임"));

        assertThat(response.nickname()).isEqualTo("새닉네임");
        assertThat(user.getNickname()).isEqualTo("새닉네임");
    }

    @Test
    @DisplayName("비밀번호 변경 시 현재 비밀번호가 틀리면 INVALID_PASSWORD 예외가 발생한다")
    void 현재_password가_틀리면_INVALID_PASSWORD다() {
        String oldHash = passwordEncoder.encode("Password123!");
        User user = User.create("test@example.com", oldHash, "테스터");
        ReflectionTestUtils.setField(user, "userId", 7L);

        when(userRepository.findByUserIdAndStatusForUpdate(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));

        ChangePasswordRequest request = new ChangePasswordRequest("WrongPassword!", "NewPassword123!");

        assertThatThrownBy(() -> userService.changePassword(7L, request))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.INVALID_PASSWORD);

        assertThat(user.getPasswordHash()).isEqualTo(oldHash);
    }

    @Test
    @DisplayName("비밀번호를 정상 변경하면 새로운 BCrypt 해시가 저장된다")
    void 새_password는_BCrypt_hash로만_저장한다() {
        String oldHash = passwordEncoder.encode("Password123!");
        User user = User.create("test@example.com", oldHash, "테스터");
        ReflectionTestUtils.setField(user, "userId", 7L);

        when(userRepository.findByUserIdAndStatusForUpdate(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));

        ChangePasswordRequest request = new ChangePasswordRequest("Password123!", "NewPassword123!");

        UserResponse response = userService.changePassword(7L, request);

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(passwordEncoder.matches("NewPassword123!", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordHash()).isNotEqualTo(oldHash);
    }

    @Test
    @DisplayName("탈퇴 시 현재 비밀번호가 틀리면 회원과 계좌 상태가 변경되지 않는다")
    void 탈퇴_password가_틀리면_user와_account를_변경하지_않는다() {
        String oldHash = passwordEncoder.encode("Password123!");
        User user = User.create("test@example.com", oldHash, "테스터");
        ReflectionTestUtils.setField(user, "userId", 7L);

        when(userRepository.findByUserIdAndStatusForUpdate(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));

        WithdrawRequest request = new WithdrawRequest("WrongPassword!");

        assertThatThrownBy(() -> userService.withdraw(7L, request))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.INVALID_PASSWORD);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(accountRepository, never()).findByUserIdAndStatusForUpdate(any(), any());
    }

    @Test
    @DisplayName("탈퇴 시 ACTIVE 계좌가 없으면 ACCOUNT_NOT_FOUND 예외가 발생한다")
    void 탈퇴_시_ACTIVE_account가_없으면_ACCOUNT_NOT_FOUND다() {
        String oldHash = passwordEncoder.encode("Password123!");
        User user = User.create("test@example.com", oldHash, "테스터");
        ReflectionTestUtils.setField(user, "userId", 7L);

        when(userRepository.findByUserIdAndStatusForUpdate(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));
        when(accountRepository.findByUserIdAndStatusForUpdate(7L, AccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        WithdrawRequest request = new WithdrawRequest("Password123!");

        assertThatThrownBy(() -> userService.withdraw(7L, request))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.ACCOUNT_NOT_FOUND);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("탈퇴 시 회원은 WITHDRAWN, 계좌는 UTC 시각 기준 CLOSED로 전환된다")
    void 탈퇴는_user를_WITHDRAWN으로_account를_UTC시각에_CLOSED로_전환한다() {
        String oldHash = passwordEncoder.encode("Password123!");
        User user = User.create("test@example.com", oldHash, "테스터");
        ReflectionTestUtils.setField(user, "userId", 7L);

        OffsetDateTime openedAt = OffsetDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC);
        Account account = Account.open(7L, 1, new BigDecimal("50000000"), openedAt);
        ReflectionTestUtils.setField(account, "accountId", 10L);

        when(userRepository.findByUserIdAndStatusForUpdate(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));
        when(accountRepository.findByUserIdAndStatusForUpdate(7L, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));

        WithdrawRequest request = new WithdrawRequest("Password123!");

        userService.withdraw(7L, request);

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedAt()).isEqualTo(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}
