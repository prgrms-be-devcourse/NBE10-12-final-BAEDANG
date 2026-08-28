package com.baedang.trading.repository;

import com.baedang.trading.entity.EntryType;
import com.baedang.trading.entity.LedgerEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커서 페이지 쿼리(nullable enum 필터 + entry_id 커서)를 실제 DB 로 검증합니다.
 * 단위 테스트는 레포를 목킹하므로 이 JPQL 자체는 여기서만 확인됩니다.
 */
@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",  // 스키마는 컨테이너가 마운트한 schema.sql 이 진실
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LedgerEntryRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql")
                            .toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired JdbcTemplate jdbc;

    private Long accountId;
    private Long depositId;
    private Long buyId;
    private Long sellId;

    @BeforeEach
    void seed() {
        Long userId = jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, nickname) VALUES (?, ?, ?) RETURNING user_id",
                Long.class, "ledger-" + System.nanoTime() + "@test.com", "x", "tester");
        accountId = jdbc.queryForObject(
                "INSERT INTO account(user_id, initial_cash, cash_balance) VALUES (?, ?, ?) RETURNING account_id",
                Long.class, userId, new BigDecimal("50000000"), new BigDecimal("50000000"));

        // order_id 는 findPage 쿼리와 무관하고(조인은 서비스 계층), trade_order FK 라 실제 주문이 필요하다.
        // 이 테스트는 커서·필터·정렬만 검증하므로 order_id 는 전부 null 로 둔다.
        depositId = insertEntry("INITIAL_DEPOSIT", "50000000", "50000000", "2026-08-10T00:00:00Z");
        buyId = insertEntry("BUY", "-2415242", "47584758", "2026-08-11T03:37:02Z");
        sellId = insertEntry("SELL", "2409928", "49994686", "2026-08-12T03:00:00Z");
    }

    @Test
    void 필터가_없으면_entry_id_내림차순으로_전체를_돌려준다() {
        List<LedgerEntry> rows = ledgerEntryRepository.findPage(accountId, null, null, PageRequest.of(0, 10));

        assertThat(rows).extracting(LedgerEntry::getEntryId)
                .containsExactly(sellId, buyId, depositId);
    }

    @Test
    void entryType_필터는_해당_유형만_돌려준다() {
        List<LedgerEntry> rows = ledgerEntryRepository.findPage(accountId, EntryType.BUY, null, PageRequest.of(0, 10));

        assertThat(rows).extracting(LedgerEntry::getEntryType).containsExactly(EntryType.BUY);
        assertThat(rows).extracting(LedgerEntry::getEntryId).containsExactly(buyId);
    }

    @Test
    void 커서보다_작은_entry_id_만_돌려준다() {
        List<LedgerEntry> rows = ledgerEntryRepository.findPage(accountId, null, sellId, PageRequest.of(0, 10));

        assertThat(rows).extracting(LedgerEntry::getEntryId).containsExactly(buyId, depositId);
    }

    @Test
    void Pageable_로_조회_개수를_제한한다() {
        List<LedgerEntry> rows = ledgerEntryRepository.findPage(accountId, null, null, PageRequest.of(0, 2));

        assertThat(rows).extracting(LedgerEntry::getEntryId).containsExactly(sellId, buyId);
    }

    private Long insertEntry(String type, String amount, String balanceAfter, String occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO ledger_entry(account_id, entry_type, amount, balance_after, exchange_rate, memo, occurred_at)
                VALUES (?, ?, ?, ?, 1, ?, ?) RETURNING entry_id
                """,
                Long.class,
                accountId, type, new BigDecimal(amount), new BigDecimal(balanceAfter),
                type + " 항목", OffsetDateTime.parse(occurredAt).atZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime());
    }
}
