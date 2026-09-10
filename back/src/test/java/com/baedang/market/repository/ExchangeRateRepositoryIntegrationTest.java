package com.baedang.market.repository;

import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.provider.ExecutionExchangeRateProviderBridge;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.global.error.BusinessException;
import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.service.ExchangeRatePersistenceService;
import com.baedang.market.service.ExchangeRateService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExchangeRateRepositoryIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    private static final OffsetDateTime COLLECTED_AT = OffsetDateTime.parse("2026-08-26T06:00:05Z");

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 오래된수신은_최신환율과_유효기간을_덮어쓰지않는다() {
        OffsetDateTime from = COLLECTED_AT.minusSeconds(5);
        exchangeRateRepository.upsertLatestObservation("USD", "KRW", new BigDecimal("1400"), null,
                from, from.plusHours(1), COLLECTED_AT.plusSeconds(10));
        assertThat(exchangeRateRepository.upsertLatestObservation("USD", "KRW", new BigDecimal("1300"), null,
                from, from.plusMinutes(1), COLLECTED_AT)).isZero();
        ExchangeRate saved = exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc("USD", "KRW").orElseThrow();
        assertThat(saved.getRate()).isEqualByComparingTo("1400");
        assertThat(saved.getValidUntil()).isEqualTo(from.plusHours(1));
    }

    @Test
    void 화면과_체결은_DB를_공유하되_표시율과_체결율을_구분하고_만료를_거절한다() {
        OffsetDateTime from = COLLECTED_AT.minusSeconds(5);
        OffsetDateTime until = from.plusHours(1);
        ExchangeRatePersistenceService persistence = new ExchangeRatePersistenceService(exchangeRateRepository);
        persistence.saveIfValid(new ExchangeRateQuote("USD", "KRW", new BigDecimal("1400.123456"),
                new BigDecimal("1398.123456"), from, until), COLLECTED_AT);
        Clock clock = Clock.fixed(COLLECTED_AT.plusMinutes(5).toInstant(), ZoneOffset.UTC);
        ExecutionExchangeRateSnapshot snapshot = new ExecutionExchangeRateProviderBridge(exchangeRateRepository, clock).currentUsdKrwSnapshot();
        assertThat(snapshot.rate()).isEqualByComparingTo("1400.123456");
        assertThat(new ExchangeRateService(exchangeRateRepository, clock).getLatest("USD", "KRW").rate()).isEqualTo("1398.123456");
        assertThatThrownBy(() -> new ExecutionExchangeRateProviderBridge(exchangeRateRepository,
                Clock.fixed(until.toInstant(), ZoneOffset.UTC)).currentUsdKrwSnapshot()).isInstanceOf(BusinessException.class);

        persistence.saveIfValid(new ExchangeRateQuote("USD", "KRW", new BigDecimal("1401.654321"),
                null, from, until.plusHours(1)), until);
        entityManager.clear();
        assertThat(new ExecutionExchangeRateProviderBridge(exchangeRateRepository,
                Clock.fixed(until.toInstant(), ZoneOffset.UTC)).currentUsdKrwRate()).isEqualByComparingTo("1401.654321");
        assertThat(snapshot.rate()).isEqualByComparingTo("1400.123456");
        assertThat(exchangeRateRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일한 통화쌍과 validFrom은 한번만 저장")
    void t1() {
        OffsetDateTime validFrom = OffsetDateTime.parse("2026-08-26T06:00:00Z");
        int first = exchangeRateRepository.upsertLatestObservation(
                "USD",
                "KRW",
                new BigDecimal("1400.250000"),
                new BigDecimal("1398.500000"),
                validFrom,validFrom.plusHours(1),
                COLLECTED_AT
        );

        int duplicate = exchangeRateRepository.upsertLatestObservation(
                "USD",
                "KRW",
                new BigDecimal("1401.000000"),
                new BigDecimal("1399.000000"),
                validFrom,validFrom.plusHours(1),
                COLLECTED_AT.plusSeconds(5)
        );

        assertThat(first).isEqualTo(1);
        assertThat(duplicate).isEqualTo(1);
        assertThat(exchangeRateRepository.count()).isEqualTo(1);

        ExchangeRate saved = exchangeRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByValidFromDesc("USD","KRW")
                .orElseThrow();

        assertThat(saved.getRate()).isEqualByComparingTo("1401.000000");
        assertThat(saved.getMidRate()).isEqualByComparingTo("1399.000000");
        assertThat(saved.getValidFrom().toInstant()).isEqualTo(validFrom.toInstant());
        assertThat(saved.getValidUntil().toInstant()).isEqualTo(validFrom.plusHours(1).toInstant());
        assertThat(saved.getCollectedAt().toInstant()).isEqualTo(COLLECTED_AT.plusSeconds(5).toInstant());
    }

    @Test
    @DisplayName("기간 조회는 validFrom을 오름차순으로 반환")
    void t2() {
        OffsetDateTime firstAt = OffsetDateTime.parse("2026-08-26T06:00:00Z");
        OffsetDateTime secondAt = OffsetDateTime.parse("2026-08-26T07:00:00Z");

        exchangeRateRepository.upsertLatestObservation(
                "USD",
                "KRW",
                new BigDecimal("1401.000000"),
                new BigDecimal("1399.000000"),
                secondAt,secondAt.plusHours(1),
                COLLECTED_AT
        );

        exchangeRateRepository.upsertLatestObservation(
                "USD",
                "KRW",
                new BigDecimal("1400.000000"),
                new BigDecimal("1398.000000"),
                firstAt,firstAt.plusHours(1),
                COLLECTED_AT
        );

        List<ExchangeRate> rows =
                exchangeRateRepository
                        .findByBaseCurrencyAndQuoteCurrencyAndValidFromGreaterThanEqualOrderByValidFromAsc(
                                "USD",
                                "KRW",
                                firstAt.minusHours(1)
                        );

        assertThat(rows.stream()
                .map(row-> row.getValidFrom().toInstant()).toList())
                .containsExactly(firstAt.toInstant(), secondAt.toInstant());

        assertThat(rows)
                .extracting(ExchangeRate::getMidRate)
                .containsExactly(new BigDecimal("1398.000000"), new BigDecimal("1399.000000"));
    }

}
