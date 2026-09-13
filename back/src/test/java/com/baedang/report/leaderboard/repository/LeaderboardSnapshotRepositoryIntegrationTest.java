package com.baedang.report.leaderboard.repository;

import com.baedang.global.config.JpaConfig;
import com.baedang.report.leaderboard.dto.TypeAggregate;
import com.baedang.report.leaderboard.entity.LeaderboardSnapshot;
import com.baedang.user.entity.Account;
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
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class LeaderboardSnapshotRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-09-11T07:30:00Z");
    private static final BigDecimal INITIAL = new BigDecimal("50000000");

    @Autowired LeaderboardSnapshotRepository snapshots;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;

    @Test
    void 지정한_배치의_분류된_유형만_평균수익률_내림차순으로_집계한다() {
        snapshot("a", AS_OF, "DKSB", "0.1");
        snapshot("b", AS_OF, "DKSB", "0.3");
        snapshot("c", AS_OF, "CGEB", "0.25");
        snapshot("d", AS_OF, null, "0.9");
        snapshot("e", AS_OF.minusDays(1), "DKSB", "0.9");
        snapshots.flush();

        List<TypeAggregate> result = snapshots.aggregateByType(AS_OF);

        assertThat(result).extracting(TypeAggregate::typeCode).containsExactly("CGEB", "DKSB");
        assertThat(result).extracting(TypeAggregate::count).containsExactly(1L, 2L);
        assertThat(result.get(0).avgReturnRate()).isEqualByComparingTo("0.25");
        assertThat(result.get(1).avgReturnRate()).isEqualByComparingTo("0.2");
        assertThat(snapshots.aggregateByType(AS_OF.plusDays(1))).isEmpty();
    }

    @Test
    void 평균은_double보다_높은_십진수_정밀도를_유지한다() {
        snapshot("a", AS_OF, "DKSB", "0.1");
        snapshot("b", AS_OF, "DKSB", "0.1");
        snapshot("c", AS_OF, "DKSB", "0.2");
        snapshots.flush();

        TypeAggregate result = snapshots.aggregateByType(AS_OF).getFirst();

        assertThat(result.count()).isEqualTo(3);
        assertThat(result.avgReturnRate().setScale(18, RoundingMode.HALF_UP))
                .isEqualByComparingTo("0.133333333333333333");
    }

    private void snapshot(String suffix, OffsetDateTime asOf, String typeCode, String returnRate) {
        User user = users.save(User.create("lb" + suffix + "@test.local", "h", "lb" + suffix));
        Account account = accounts.save(Account.open(user.getUserId(), 1, INITIAL, AS_OF.minusWeeks(5)));
        snapshots.save(LeaderboardSnapshot.of(asOf, account.getAccountId(), user.getUserId(), 1,
                INITIAL, new BigDecimal(returnRate), 1, 3, typeCode));
    }
}
