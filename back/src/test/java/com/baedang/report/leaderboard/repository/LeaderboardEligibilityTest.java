package com.baedang.report.leaderboard.repository;

import com.baedang.global.config.JpaConfig;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.User;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리더보드 자격 쿼리의 경계·시드 코호트 검증(설계문서 §6.4). 자격 = opened_at + 4주를 채운
 * ACTIVE 계좌. 시드 포함 여부는 코호트(쿼리) 시점에 정한다.
 */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class LeaderboardEligibilityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T00:00:00Z");
    private static final OffsetDateTime THRESHOLD = NOW.minusWeeks(4);
    private static final BigDecimal INITIAL = new BigDecimal("50000000");

    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;

    private long openAccount(String suffix, boolean seed, OffsetDateTime openedAt) {
        User user = seed
                ? User.createSeed("u" + suffix + "@seed.local", "h", "nick" + suffix)
                : User.create("u" + suffix + "@real.local", "h", "nick" + suffix);
        Long userId = users.save(user).getUserId();
        return accounts.save(Account.open(userId, 1, INITIAL, openedAt)).getAccountId();
    }

    @Test
    void 경계_포함과_시드_코호트를_구분한다() {
        long eligible = openAccount("boundary", false, THRESHOLD);            // opened_at == threshold → 포함
        long tooNew = openAccount("toonew", false, THRESHOLD.plusSeconds(1));  // 1초 뒤 → 제외
        long seedEligible = openAccount("seed", true, THRESHOLD.minusDays(3)); // 오래됐지만 시드

        // 시드 제외(프로덕션 기본): 경계 계좌만.
        assertThat(accounts.findLeaderboardEligible(AccountStatus.ACTIVE, THRESHOLD, false))
                .extracting(Account::getAccountId)
                .containsExactly(eligible)
                .doesNotContain(tooNew, seedEligible);

        // 시드 포함(dev/데모): 경계 + 시드, 여전히 too-new 는 제외.
        assertThat(accounts.findLeaderboardEligible(AccountStatus.ACTIVE, THRESHOLD, true))
                .extracting(Account::getAccountId)
                .containsExactlyInAnyOrder(eligible, seedEligible)
                .doesNotContain(tooNew);
    }
}
