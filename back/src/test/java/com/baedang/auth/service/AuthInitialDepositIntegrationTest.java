package com.baedang.auth.service;

import com.baedang.account.service.AccountResetService;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.trading.service.InitialDepositLedgerService;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 테스트 자체의 트랜잭션 없이 호출하여 실제 커밋·롤백 결과를 확인합니다. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "logging.level.org.hibernate.SQL=OFF",
        "trading.initial-cash=12345678.1234"
})
class AuthInitialDepositIntegrationTest {

    private static final BigDecimal INITIAL_CASH = new BigDecimal("12345678.1234");
    private static final Instant NOW = Instant.parse("2026-09-02T06:00:00Z");
    private static final SignUpRequest REQUEST = new SignUpRequest("new@example.com", "password123", "신규회원");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql").toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @MockitoBean Clock clock;
    @MockitoBean MarketCalendarPort marketCalendarPort;
    @Autowired AuthService authService;
    @Autowired AccountResetService resetService;
    @Autowired InitialDepositLedgerService initialDepositLedgerService;
    @Autowired AccountRepository accountRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE ledger_entry, holding, trade_order, account, users RESTART IDENTITY CASCADE");
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void 가입은_설정된_초기금으로_회원_계좌_원장을_함께_커밋한다() {
        Long userId = authService.signUp(REQUEST).userId();
        Account account = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE).orElseThrow();
        assertThat(account.getRoundNo()).isEqualTo(1);
        assertThat(account.getInitialCash()).isEqualByComparingTo(INITIAL_CASH);
        assertThat(account.getCashBalance()).isEqualByComparingTo(INITIAL_CASH);
        assertThat(account.getOpenedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertInitialDeposit(account, "모의투자금 지급");
        assertCounts(1, 1, 1);
    }

    @Test
    void 같은_이메일로_재가입하면_계좌와_원장을_추가하지_않는다() {
        authService.signUp(REQUEST);
        assertThatThrownBy(() -> authService.signUp(REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EMAIL_DUPLICATED));
        assertCounts(1, 1, 1);
    }

    @Test
    void 닉네임_중복_가입도_계좌와_원장을_추가하지_않는다() {
        authService.signUp(REQUEST);
        assertThatThrownBy(() -> authService.signUp(new SignUpRequest(
                "other@example.com", REQUEST.password(), REQUEST.nickname())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NICKNAME_DUPLICATED));
        assertCounts(1, 1, 1);
    }

    @Test
    void 가입_원장_INSERT가_실패하면_회원과_계좌도_롤백한다() {
        jdbc.execute("ALTER TABLE ledger_entry ADD CONSTRAINT ck_test_reject_signup "
                + "CHECK (memo <> '모의투자금 지급')");
        try {
            assertThatThrownBy(() -> authService.signUp(REQUEST)).isInstanceOf(RuntimeException.class);
            assertCounts(0, 0, 0);
        } finally {
            jdbc.execute("ALTER TABLE ledger_entry DROP CONSTRAINT ck_test_reject_signup");
        }
        // 원장 장애 해소 후 같은 가입 요청으로 정상 생성할 수 있습니다.
        authService.signUp(REQUEST);
        assertCounts(1, 1, 1);
    }

    @Test
    void 가입후_초기화와_재요청에도_각_계좌의_원장은_하나이고_잔액합이_일치한다() {
        Long userId = authService.signUp(REQUEST).userId();
        Account first = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE).orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var reset = resetService.reset(userId, first.getAccountId());
        assertThat(resetService.reset(userId, first.getAccountId())).isEqualTo(reset);

        Account closed = accountRepository.findById(first.getAccountId()).orElseThrow();
        Account opened = accountRepository.findById(reset.accountId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(opened.getOpenedAt()).isEqualTo(closed.getClosedAt());
        assertInitialDeposit(closed, "모의투자금 지급");
        assertInitialDeposit(opened, "모의투자금 지급 · 2회차");
        assertCounts(1, 2, 2);
    }

    @Test
    void 트랜잭션_없이_초기원장만_저장하는_호출은_거절한다() {
        assertThatThrownBy(() -> initialDepositLedgerService.recordInitialDeposit(
                1L, INITIAL_CASH, 1, NOW.atOffset(ZoneOffset.UTC)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertCounts(0, 0, 0);
    }

    private void assertInitialDeposit(Account account, String expectedMemo) {
        var rows = jdbc.query("SELECT entry_type, amount, balance_after, exchange_rate, order_id, memo, occurred_at "
                        + "FROM ledger_entry WHERE account_id = ?",
                (rs, n) -> new InitialRow(rs.getString("entry_type"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after"), rs.getBigDecimal("exchange_rate"),
                        rs.getObject("order_id", Long.class), rs.getString("memo"),
                        rs.getObject("occurred_at", OffsetDateTime.class)), account.getAccountId());
        assertThat(rows).hasSize(1);
        InitialRow row = rows.getFirst();
        assertThat(row.type()).isEqualTo("INITIAL_DEPOSIT");
        assertThat(row.amount()).isEqualByComparingTo(account.getInitialCash());
        assertThat(row.balance()).isEqualByComparingTo(account.getInitialCash());
        assertThat(row.rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(row.orderId()).isNull();
        assertThat(row.memo()).isEqualTo(expectedMemo);
        assertThat(row.at()).isEqualTo(account.getOpenedAt());
        BigDecimal sum = jdbc.queryForObject("SELECT SUM(amount) FROM ledger_entry WHERE account_id = ?",
                BigDecimal.class, account.getAccountId());
        assertThat(sum).isEqualByComparingTo(account.getCashBalance());
    }

    private void assertCounts(long users, long accounts, long ledgers) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(users);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account", Long.class)).isEqualTo(accounts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry", Long.class)).isEqualTo(ledgers);
    }

    private record InitialRow(String type, BigDecimal amount, BigDecimal balance, BigDecimal rate,
                              Long orderId, String memo, OffsetDateTime at) {
    }
}
