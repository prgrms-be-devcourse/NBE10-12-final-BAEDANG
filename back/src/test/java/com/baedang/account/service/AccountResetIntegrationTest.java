package com.baedang.account.service;

import com.baedang.account.dto.AccountResetResponse;
import com.baedang.trading.entity.EntryType;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.User;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "logging.level.org.hibernate.SQL=OFF"
})
class AccountResetIntegrationTest {

    private static final BigDecimal INITIAL_CASH = new BigDecimal("50000000");
    private static final Instant RESET_INSTANT = Instant.parse("2026-08-27T04:30:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql")
                            .toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @MockitoBean Clock clock;

    @Autowired AccountResetService accountResetService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_entry, holding, trade_order, account, users RESTART IDENTITY CASCADE");
        when(clock.instant()).thenReturn(RESET_INSTANT);
    }

    @Test
    void 초기화는_기존_회차를_보존하고_새_계좌와_초기지급_원장을_함께_만든다() {
        Fixture fixture = createFixture();
        OffsetDateTime resetAt = RESET_INSTANT.atOffset(ZoneOffset.UTC);

        AccountResetResponse response = accountResetService.reset(fixture.userId(), fixture.accountId());

        Account oldAccount = accountRepository.findById(fixture.accountId()).orElseThrow();
        Account newAccount = accountRepository.findById(response.accountId()).orElseThrow();
        assertThat(oldAccount.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(oldAccount.getClosedAt()).isEqualTo(resetAt);
        assertThat(newAccount.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(newAccount.getRoundNo()).isEqualTo(2);
        assertThat(newAccount.getOpenedAt()).isEqualTo(resetAt);
        assertThat(newAccount.getCashBalance()).isEqualByComparingTo(INITIAL_CASH);
        assertThat(newAccount.getLockedCash()).isEqualByComparingTo(BigDecimal.ZERO);

        List<LedgerRow> ledgers = jdbcTemplate.query(
                "select entry_type, amount, balance_after, order_id, occurred_at "
                        + "from ledger_entry where account_id = ? order by entry_id",
                (rs, rowNum) -> new LedgerRow(
                        EntryType.valueOf(rs.getString("entry_type")),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after"),
                        rs.getObject("order_id", Long.class),
                        rs.getObject("occurred_at", OffsetDateTime.class)),
                newAccount.getAccountId());
        assertThat(ledgers).hasSize(1);
        LedgerRow initialDeposit = ledgers.getFirst();
        assertThat(initialDeposit.entryType()).isEqualTo(EntryType.INITIAL_DEPOSIT);
        assertThat(initialDeposit.amount()).isEqualByComparingTo(INITIAL_CASH);
        assertThat(initialDeposit.balanceAfter()).isEqualByComparingTo(INITIAL_CASH);
        assertThat(initialDeposit.orderId()).isNull();
        assertThat(initialDeposit.occurredAt()).isEqualTo(resetAt);
        assertThat(ledgerEntryRepository.countByAccountId(fixture.accountId())).isEqualTo(1);
    }

    @Test
    void 동일한_계좌_ID로_재시도하면_새_회차와_원장을_추가하지_않는다() {
        Fixture fixture = createFixture();
        AccountResetResponse first = accountResetService.reset(fixture.userId(), fixture.accountId());
        Account resetAccount = accountRepository.findById(first.accountId()).orElseThrow();
        resetAccount.creditMarketSell(new BigDecimal("100"));
        accountRepository.saveAndFlush(resetAccount);

        AccountResetResponse retry = accountResetService.reset(fixture.userId(), fixture.accountId());

        assertThat(retry).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from account where user_id = ?", Long.class, fixture.userId()))
                .isEqualTo(2L);
        assertThat(ledgerEntryRepository.countByAccountId(first.accountId())).isEqualTo(1);
    }

    @Test
    void 동시에_같은_초기화를_요청해도_새_회차는_하나만_생긴다() throws Exception {
        Fixture fixture = createFixture();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<AccountResetResponse> first = executor.submit(
                    () -> accountResetService.reset(fixture.userId(), fixture.accountId()));
            Future<AccountResetResponse> second = executor.submit(
                    () -> accountResetService.reset(fixture.userId(), fixture.accountId()));

            AccountResetResponse firstResponse = first.get(10, TimeUnit.SECONDS);
            AccountResetResponse secondResponse = second.get(10, TimeUnit.SECONDS);

            assertThat(secondResponse).isEqualTo(firstResponse);
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from account where user_id = ?", Long.class, fixture.userId()))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from account where user_id = ? and status = 'ACTIVE'",
                Long.class,
                fixture.userId())).isEqualTo(1L);
    }

    @Test
    void 초기지급_원장_INSERT가_실패하면_계좌_종료와_신규_개설도_롤백한다() {
        Fixture fixture = createFixture();
        jdbcTemplate.execute("alter table ledger_entry add constraint ck_test_reject_reset "
                + "check (memo <> '모의투자금 지급 · 2회차')");

        try {
            assertThatThrownBy(() -> accountResetService.reset(fixture.userId(), fixture.accountId()))
                    .isInstanceOf(RuntimeException.class);

            Account current = accountRepository.findById(fixture.accountId()).orElseThrow();
            assertThat(current.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(current.getClosedAt()).isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from account where user_id = ?", Long.class, fixture.userId()))
                    .isEqualTo(1L);
            assertThat(ledgerEntryRepository.countByAccountId(fixture.accountId())).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("alter table ledger_entry drop constraint if exists ck_test_reject_reset");
        }
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString();
        User user = userRepository.save(User.create(
                suffix + "@example.com", "password-hash", "user-" + suffix.substring(0, 8)));
        OffsetDateTime openedAt = RESET_INSTANT.minusSeconds(3600).atOffset(ZoneOffset.UTC);
        Account account = accountRepository.saveAndFlush(
                Account.open(user.getUserId(), 1, INITIAL_CASH, openedAt));
        ledgerEntryRepository.save(LedgerEntry.initialDeposit(
                account.getAccountId(), INITIAL_CASH, "모의투자금 지급 · 1회차", openedAt));
        return new Fixture(user.getUserId(), account.getAccountId());
    }

    private record Fixture(Long userId, Long accountId) {
    }

    private record LedgerRow(
            EntryType entryType,
            BigDecimal amount,
            BigDecimal balanceAfter,
            Long orderId,
            OffsetDateTime occurredAt
    ) {
    }
}
