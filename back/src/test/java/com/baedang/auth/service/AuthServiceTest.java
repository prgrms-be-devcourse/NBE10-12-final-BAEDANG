package com.baedang.auth.service;

import com.baedang.auth.dto.AccessTokenResponse;
import com.baedang.auth.dto.AuthResponse;
import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.RefreshTokenRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.service.AuthSessionService.Tokens;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.trading.service.LedgerService;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.User;
import com.baedang.user.entity.UserStatus;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private UserRepository userRepository;
    private AccountRepository accountRepository;
    private LedgerService ledgerService;
    private AuthSessionService sessions;
    private PasswordEncoder passwordEncoder;
    private Clock clock;
    private AuthService authService;

    private final BigDecimal initialCash = new BigDecimal("50000000");
    private final Instant now = Instant.parse("2026-09-02T00:00:00Z");

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        accountRepository = mock(AccountRepository.class);
        ledgerService = mock(LedgerService.class);
        sessions = mock(AuthSessionService.class);
        passwordEncoder = new BCryptPasswordEncoder();
        clock = Clock.fixed(now, ZoneOffset.UTC);

        authService = new AuthService(
                userRepository,
                accountRepository,
                ledgerService,
                passwordEncoder,
                sessions,
                initialCash,
                clock
        );
    }

    @Test
    @DisplayName("회원가입은 user와 account, 초기 지급 원장과 두 token을 만든다")
    void t1() {
        SignUpRequest request = new SignUpRequest(
                "test@example.com","Password123!","테스터");

        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByNickname("테스터")).thenReturn(false);

        User savedUser = User
                .create("test@example.com","hashed-pw","테스터");
        ReflectionTestUtils.setField(savedUser, "userId", 1L);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);

        Account savedAccount = Account
                .open(1L,1,initialCash,OffsetDateTime.ofInstant(now,ZoneOffset.UTC));
        ReflectionTestUtils.setField(savedAccount, "accountId", 10L);
        when(accountRepository.save(any(Account.class))).thenReturn(savedAccount);

        when(sessions.create(1L)).thenReturn(new Tokens("mock-access-token", "mock-refresh-token", now.plusSeconds(604800)));

        AuthResponse response = authService.signUp(request);

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.nickname()).isEqualTo("테스터");
        assertThat(response.accessToken()).isEqualTo("mock-access-token");
        assertThat(response.refreshToken()).isEqualTo("mock-refresh-token");
        assertThat(response.account().accountId()).isEqualTo(10L);
        assertThat(response.account().initialCash()).isEqualTo("50000000");

        verify(ledgerService).recordInitialDeposit(
                10L,
                initialCash,
                1,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        verify(sessions).create(1L);
    }

    @Test
    @DisplayName("가입 INSERT의 닉네임 UNIQUE 충돌은 NICKNAME_DUPLICATED다")
    void 가입_INSERT의_닉네임_UK_충돌을_매핑한다() {
        SignUpRequest request = new SignUpRequest("other@example.com", "Password123!", "테스터");
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userRepository.existsByNickname(request.nickname())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(
                new DataIntegrityViolationException("nickname conflict",
                        new ConstraintViolationException("nickname conflict", new SQLException(),
                                "uq_users_nickname")));

        assertThatThrownBy(() -> authService.signUp(request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NICKNAME_DUPLICATED));
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("로그인은 ACTIVE user와 account 두 token을 발급")
    void t2() {
        String rawPassword = "Password123!";
        String encodedPassword = passwordEncoder.encode(rawPassword);
        User user = User.create("test@example.com", encodedPassword, "테스터");
        ReflectionTestUtils.setField(user, "userId", 1L);

        Account account = Account.open(1L, 1, initialCash, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        ReflectionTestUtils.setField(account, "accountId", 10L);

        when(userRepository.findByEmailForUpdate("test@example.com")).thenReturn(Optional.of(user));
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE)).thenReturn(Optional.of(account));
        when(sessions.create(1L)).thenReturn(new Tokens("mock-access-token", "mock-refresh-token", now.plusSeconds(604800)));

        AuthResponse response = authService.login(new LoginRequest("test@example.com", rawPassword));

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.accessToken()).isEqualTo("mock-access-token");
        assertThat(response.refreshToken()).isEqualTo("mock-refresh-token");
        assertThat(response.account().accountId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("없는 email과 틀린 password와 WITHDRAWN user는 모두 LOGIN_FAILED다")
    void t4() {
        when(userRepository.findByEmailForUpdate("none@example.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login(new LoginRequest("none@example.com", "Password123!")))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.LOGIN_FAILED);

        User user = User.create("test@example.com", passwordEncoder.encode("Correct123!"), "테스터");
        when(userRepository.findByEmailForUpdate("test@example.com")).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "WrongPassword!")))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.LOGIN_FAILED);

        ReflectionTestUtils.setField(user, "status", UserStatus.WITHDRAWN);
        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "Correct123!")))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("없는 email도 dummy BCrypt를 수행해 로그인 실패 시간을 평준화한다")
    void 없는_email도_dummy_BCrypt를_수행한다() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthService service = new AuthService(
                userRepository,
                accountRepository,
                ledgerService,
                encoder,
                sessions,
                initialCash,
                clock);
        when(userRepository.findByEmailForUpdate("none@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("none@example.com", "Password123!")))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.LOGIN_FAILED);
        verify(encoder).matches(eq("Password123!"), argThat(hash -> hash != null && !hash.isBlank()));
    }

    @Test
    @DisplayName("DORMANT user는 LOGIN_FAILED이고 token을 발급하지 않는다")
    void DORMANT_user는_로그인할_수_없다() {
        String rawPassword = "Password123!";
        User user = User.create(
                "test@example.com",
                passwordEncoder.encode(rawPassword),
                "테스터"
        );
        ReflectionTestUtils.setField(user, "userId", 1L);
        ReflectionTestUtils.setField(user, "status", UserStatus.DORMANT);
        when(userRepository.findByEmailForUpdate("test@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("test@example.com", rawPassword)))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.LOGIN_FAILED);
        verify(sessions, never()).create(anyLong());
    }
    @Test
    @DisplayName("ACTIVE account가 없으면 ACCOUNT_NOT_FOUND")
    void t5() {
        String rawPassword = "Password123!";
        User user = User.create("test@example.com", passwordEncoder.encode(rawPassword), "테스터");
        ReflectionTestUtils.setField(user, "userId", 1L);

        when(userRepository.findByEmailForUpdate("test@example.com")).thenReturn(Optional.of(user));
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", rawPassword)))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("회원가입 원장저장이 실패시 예외 알리고 token 발급하지 않음")
    void t6() {
        SignUpRequest request = new SignUpRequest(
                "test@example.com",
                "Password123!",
                "테스터"
        );
        OffsetDateTime openedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        when(userRepository.existsByEmail("test@example.com"))
                .thenReturn(false);
        when(userRepository.existsByNickname("테스터"))
                .thenReturn(false);

        User savedUser = User.create(
                "test@example.com",
                "hashed-password",
                "테스터"
        );
        ReflectionTestUtils.setField(savedUser, "userId", 1L);

        when(userRepository.saveAndFlush(any(User.class)))
                .thenReturn(savedUser);

        Account savedAccount = Account.open(
                1L,
                1,
                initialCash,
                openedAt
        );
        ReflectionTestUtils.setField(savedAccount, "accountId", 10L);

        when(accountRepository.save(any(Account.class)))
                .thenReturn(savedAccount);

        RuntimeException ledgerFailure = new RuntimeException("ledger failure");

        doThrow(ledgerFailure)
                .when(ledgerService)
                .recordInitialDeposit(
                        10L,
                        initialCash,
                        1,
                        openedAt
                );

        assertThatThrownBy(() -> authService.signUp(request))
                .isSameAs(ledgerFailure);

        verify(sessions, never()).create(any());
    }

    @Test
    void 갱신은_세션서비스에서_발급한_토큰쌍을_반환한다() {
        when(sessions.rotate("refresh-token"))
                .thenReturn(new Tokens("new-access", "new-refresh", now.plusSeconds(60)));
        assertThat(authService.refresh(new RefreshTokenRequest("refresh-token")))
                .isEqualTo(new AccessTokenResponse("new-access", "new-refresh", now.plusSeconds(60)));
    }

    @Test
    void 갱신_실패는_그대로_전달한다() {
        BusinessException failure = new BusinessException(ErrorCode.REFRESH_TOKEN_REUSED);
        when(sessions.rotate("used-token")).thenThrow(failure);
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("used-token"))).isSameAs(failure);
    }
}
