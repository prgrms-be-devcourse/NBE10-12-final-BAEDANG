package com.baedang.auth.service;

import com.baedang.auth.dto.AuthResponse;
import com.baedang.auth.dto.AccessTokenResponse;
import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.RefreshTokenRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.trading.service.LedgerService;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.User;
import com.baedang.user.entity.UserStatus;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import org.hibernate.exception.ConstraintViolationException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
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


class AuthServiceTest {
    private UserRepository userRepository;
    private AccountRepository accountRepository;
    private LedgerService ledgerService;
    private JwtTokenProvider jwtTokenProvider;
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
        jwtTokenProvider = mock(JwtTokenProvider.class);
        passwordEncoder = new BCryptPasswordEncoder();
        clock = Clock.fixed(now, ZoneOffset.UTC);

        authService = new AuthService(
                userRepository,
                accountRepository,
                ledgerService,
                passwordEncoder,
                jwtTokenProvider,
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

        when(jwtTokenProvider.createAccessToken(1L)).thenReturn("mock-access-token");
        when(jwtTokenProvider.createRefreshToken(1L)).thenReturn("mock-refresh-token");

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
        verify(jwtTokenProvider).createAccessToken(1L);
        verify(jwtTokenProvider).createRefreshToken(1L);
    }

    @Test
    @DisplayName("가입 INSERT의 닉네임 UNIQUE 충돌은 NICKNAME_DUPLICATED다")
    void 가입_INSERT의_닉네임_UK_충돌을_매핑한다() {
        SignUpRequest request = new SignUpRequest("other@example.com", "Password123!", "테스터");
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userRepository.existsByNickname(request.nickname())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(
                new DataIntegrityViolationException("nickname conflict",
                        new ConstraintViolationException("nickname conflict", new java.sql.SQLException(),
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

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(accountRepository.findByUserIdAndStatus(1L, AccountStatus.ACTIVE)).thenReturn(Optional.of(account));
        when(jwtTokenProvider.createAccessToken(1L)).thenReturn("mock-access-token");
        when(jwtTokenProvider.createRefreshToken(1L)).thenReturn("mock-refresh-token");

        AuthResponse response = authService.login(new LoginRequest("test@example.com", rawPassword));

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.accessToken()).isEqualTo("mock-access-token");
        assertThat(response.refreshToken()).isEqualTo("mock-refresh-token");
        assertThat(response.account().accountId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("없는 email과 틀린 password와 WITHDRAWN user는 모두 LOGIN_FAILED다")
    void t4() {
        when(userRepository.findByEmail("none@example.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login(new LoginRequest("none@example.com", "Password123!")))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.LOGIN_FAILED);

        User user = User.create("test@example.com", passwordEncoder.encode("Correct123!"), "테스터");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
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
                jwtTokenProvider,
                initialCash,
                clock);
        when(userRepository.findByEmail("none@example.com")).thenReturn(Optional.empty());

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
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("test@example.com", rawPassword)))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.LOGIN_FAILED);
        verify(jwtTokenProvider, never()).createAccessToken(anyLong());
        verify(jwtTokenProvider, never()).createRefreshToken(anyLong());
    }
    @Test
    @DisplayName("ACTIVE account가 없으면 ACCOUNT_NOT_FOUND")
    void t5() {
        String rawPassword = "Password123!";
        User user = User.create("test@example.com", passwordEncoder.encode(rawPassword), "테스터");
        ReflectionTestUtils.setField(user, "userId", 1L);

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
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

        verify(jwtTokenProvider, never()).createAccessToken(any());
        verify(jwtTokenProvider, never()).createRefreshToken(any());
    }

    @Test
    @DisplayName("유효한 refresh token과 ACTIVE 회원이면 새 access token만 발급")
    void t7() {
        User user = User.create(
                "test@example.com",
                "encoded-password",
                "테스터"
        );
        ReflectionTestUtils.setField(user, "userId", 7L);

        when(jwtTokenProvider.parseRefreshToken("refresh-token"))
                .thenReturn(7L);
        when(userRepository.findByUserIdAndStatus(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.of(user));
        when(jwtTokenProvider.createAccessToken(7L))
                .thenReturn("new-access");

        AccessTokenResponse response =
                authService.refresh(new RefreshTokenRequest("refresh-token"));

        assertThat(response)
                .isEqualTo(new AccessTokenResponse("new-access"));

        verify(jwtTokenProvider, never()).createRefreshToken(anyLong());

    }

    @Test
    @DisplayName("만료된 refresh token은 TOKEN_EXPIRED")
    void t8() {
        when(jwtTokenProvider.parseRefreshToken("expired-refresh"))
                .thenThrow(mock(ExpiredJwtException.class));

        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenRequest("expired-refresh"))
        )
                .isInstanceOf(BusinessException.class)
                .matches(exception ->
                        ((BusinessException) exception).getErrorCode()
                                == ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("access token을 refresh API에 사용하면 INVALID_TOKEN")
    void t9() {
        when(jwtTokenProvider.parseRefreshToken("access-token"))
                .thenThrow(new JwtException("token_type mismatch"));

        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenRequest("access-token"))
        )
                .isInstanceOf(BusinessException.class)
                .matches(exception ->
                        ((BusinessException) exception).getErrorCode()
                                == ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("ACTIVE 회원이 아니면 refresh token을 거절")
    void t10() {
        when(jwtTokenProvider.parseRefreshToken("refresh-token"))
                .thenReturn(7L);
        when(userRepository.findByUserIdAndStatus(7L, UserStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                authService.refresh(new RefreshTokenRequest("refresh-token"))
        )
                .isInstanceOf(BusinessException.class)
                .matches(exception ->
                        ((BusinessException) exception).getErrorCode()
                                == ErrorCode.INVALID_TOKEN);

        verify(jwtTokenProvider, never()).createAccessToken(anyLong());
    }
}
