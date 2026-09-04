package com.baedang.auth;

import com.baedang.auth.dto.AccessTokenResponse;
import com.baedang.auth.dto.AuthResponse;
import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.RefreshTokenRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.user.dto.ChangePasswordRequest;
import com.baedang.user.dto.UpdateNicknameRequest;
import com.baedang.user.dto.WithdrawRequest;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.UserStatus;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "logging.level.org.hibernate.SQL=OFF",
        "auth.jwt.access-ttl=15m",
        "trading.initial-cash=50000000"
})
@AutoConfigureMockMvc
class AuthLifecycleIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-02T06:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql").toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private MarketCalendarPort marketCalendarPort;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_entry, holding, trade_order, account, users RESTART IDENTITY CASCADE");
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    @DisplayName("보호 API는 token이 없거나 X-User-Id만 있으면 UNAUTHORIZED다")
    void 보호_API는_token이_없거나_X_User_Id만_있으면_UNAUTHORIZED다() throws Exception {
        mockMvc.perform(get("/api/accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/accounts/me")
                        .header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("access_token으로 보호 API를 호출할 수 있다")
    void access_token으로_보호_API를_호출할_수_있다() throws Exception {
        SignUpRequest signUpRequest = new SignUpRequest("user1@example.com", "Password123!", "유저1");
        String responseBody = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        AuthResponse authResponse = objectMapper.readValue(responseBody, AuthResponse.class);

        mockMvc.perform(get("/api/accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authResponse.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(authResponse.account().accountId()))
                .andExpect(jsonPath("$.roundNo").value(1));
    }

    @Test
    @DisplayName("refresh_token을 Bearer로 보내면 INVALID_TOKEN이다")
    void refresh_token을_Bearer로_보내면_INVALID_TOKEN이다() throws Exception {
        SignUpRequest signUpRequest = new SignUpRequest("user2@example.com", "Password123!", "유저2");
        String responseBody = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        AuthResponse authResponse = objectMapper.readValue(responseBody, AuthResponse.class);

        mockMvc.perform(get("/api/accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authResponse.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("변조된 token은 INVALID_TOKEN이다")
    void 변조된_token은_INVALID_TOKEN이다() throws Exception {
        String validToken = jwtTokenProvider.createAccessToken(1L);
        String tamperedToken = validToken + "tampered";

        mockMvc.perform(get("/api/accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("만료된 token은 TOKEN_EXPIRED다")
    void 만료된_token은_TOKEN_EXPIRED다() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        String token = jwtTokenProvider.createAccessToken(1L);

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofMinutes(16)));

        mockMvc.perform(get("/api/accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("공개 stock API는 token없이 security filter를 통과한다")
    void 공개_stock_API는_token없이_security_filter를_통과한다() throws Exception {
        mockMvc.perform(get("/api/stocks/search").param("q", "삼성"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("기존 auth me 경로는 404다")
    void 기존_auth_me_경로는_404다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L);
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 경로를 찾을 수 없어요"));
    }

    @Test
    @DisplayName("회원가입 -> 로그인 -> 갱신 -> 조회 -> 수정 -> 비밀번호변경 -> 탈퇴 전체 생명주기 검증")
    void 회원가입_로그인_토큰갱신_정보수정_비밀번호변경_탈퇴의_전체_생명주기를_검증한다() throws Exception {
        // 1. signup
        SignUpRequest signUpRequest = new SignUpRequest("lifecycle@example.com", "Password123!", "라이프사이클");
        String signupJson = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        AuthResponse signupResponse = objectMapper.readValue(signupJson, AuthResponse.class);
        Long userId = signupResponse.userId();
        Long accountId = signupResponse.account().accountId();

        // 2. login
        LoginRequest loginRequest = new LoginRequest("lifecycle@example.com", "Password123!");
        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        AuthResponse loginResponse = objectMapper.readValue(loginJson, AuthResponse.class);
        String loginRefreshToken = loginResponse.refreshToken();

        // 3. refresh
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(loginRefreshToken);
        String refreshJson = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        AccessTokenResponse refreshResponse = objectMapper.readValue(refreshJson, AccessTokenResponse.class);
        String refreshedAccessToken = refreshResponse.accessToken();

        // 4. GET /api/users/me
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.email").value("lifecycle@example.com"))
                .andExpect(jsonPath("$.nickname").value("라이프사이클"));

        // 5. PATCH nickname
        UpdateNicknameRequest nicknameRequest = new UpdateNicknameRequest("새닉네임");
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nicknameRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"));

        // 6. PUT password
        ChangePasswordRequest passwordRequest = new ChangePasswordRequest("Password123!", "NewPassword123!");
        mockMvc.perform(put("/api/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(passwordRequest)))
                .andExpect(status().isOk());

        // 7. old-password login rejection
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("lifecycle@example.com", "Password123!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        // 8. new-password login success
        String newLoginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("lifecycle@example.com", "NewPassword123!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        AuthResponse newLoginResponse = objectMapper.readValue(newLoginJson, AuthResponse.class);
        String latestAccessToken = newLoginResponse.accessToken();
        String latestRefreshToken = newLoginResponse.refreshToken();

        // 9. DELETE withdrawal
        WithdrawRequest withdrawRequest = new WithdrawRequest("NewPassword123!");
        mockMvc.perform(delete("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + latestAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withdrawRequest)))
                .andExpect(status().isOk());

        // 10. login rejection after withdrawal
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("lifecycle@example.com", "NewPassword123!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        // 11. refresh rejection after withdrawal
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(latestRefreshToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

        // Persisted state assertions
        assertThat(userRepository.findById(userId).orElseThrow().getStatus())
                .isEqualTo(UserStatus.WITHDRAWN);
        assertThat(accountRepository.findById(accountId).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.CLOSED);
        assertThat(ledgerEntryRepository.countByAccountId(accountId)).isEqualTo(1L);

        Map<String, Object> ledgerRow = jdbcTemplate.queryForMap(
                "SELECT entry_type, amount, order_id, occurred_at FROM ledger_entry WHERE account_id = ?",
                accountId);
        assertThat(ledgerRow.get("entry_type")).isEqualTo("INITIAL_DEPOSIT");
        assertThat(new BigDecimal(ledgerRow.get("amount").toString()))
                .isEqualByComparingTo(new BigDecimal("50000000"));
        assertThat(ledgerRow.get("order_id")).isNull();
        OffsetDateTime occurredAt = ((java.sql.Timestamp) ledgerRow.get("occurred_at"))
                .toInstant().atOffset(ZoneOffset.UTC);
        assertThat(occurredAt).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("다른 회원의 accountId로 초기화 요청시 ACCOUNT_NOT_FOUND 이며 상태가 유지된다")
    void 다른_회원의_accountId로_초기화_요청시_ACCOUNT_NOT_FOUND_이며_상태가_유지된다() throws Exception {
        // User A
        SignUpRequest userARequest = new SignUpRequest("usera@example.com", "Password123!", "유저A");
        String resA = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userARequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        AuthResponse authA = objectMapper.readValue(resA, AuthResponse.class);

        // User B
        SignUpRequest userBRequest = new SignUpRequest("userb@example.com", "Password123!", "유저B");
        String resB = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userBRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        AuthResponse authB = objectMapper.readValue(resB, AuthResponse.class);

        Long accountIdB = authB.account().accountId();

        // User A의 토큰으로 User B의 accountId 초기화 시도
        mockMvc.perform(post("/api/accounts/me/reset")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + accountIdB + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        // B의 계좌는 여전히 ACTIVE 상태 유지, roundNo도 1 유지
        Account accountB = accountRepository.findById(accountIdB).orElseThrow();
        assertThat(accountB.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(accountB.getRoundNo()).isEqualTo(1);

        // B의 원장 수도 여전히 1개 (초기 지급 1건만 존재)
        assertThat(ledgerEntryRepository.countByAccountId(accountIdB)).isEqualTo(1L);

        // 전체 계좌 수도 2개 그대로 (User A 1개, User B 1개)
        assertThat(accountRepository.count()).isEqualTo(2L);
    }
}
