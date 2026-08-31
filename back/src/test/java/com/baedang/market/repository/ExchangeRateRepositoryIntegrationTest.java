package com.baedang.market.repository;

import com.baedang.market.entity.ExchangeRate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExchangeRateRepositoryIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(
                            Path.of("..","infra","schema.sql").toAbsolutePath().normalize()
                    ),"/docker-entrypoint-initdb.d/01-schema.sql"
            );

    private static final OffsetDateTime COLLECTED_AT = OffsetDateTime.parse("2026-08-26T06:00:05Z");

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @Test
    @DisplayName("동일한 통화쌍과 rateAt은 한번만 저장")
    void t1() {
        OffsetDateTime rateAt = OffsetDateTime.parse("2026-08-26T06:00:00Z");
        int first = exchangeRateRepository.insertIgnoreDuplicate(
                "USD",
                "KRW",
                new BigDecimal("1400.250000"),
                new BigDecimal("1398.500000"),
                rateAt,
                COLLECTED_AT
        );

        int duplicate = exchangeRateRepository.insertIgnoreDuplicate(
                "USD",
                "KRW",
                new BigDecimal("1401.000000"),
                new BigDecimal("1399.000000"),
                rateAt,
                COLLECTED_AT.plusSeconds(5)
        );

        assertThat(first).isEqualTo(1);
        assertThat(duplicate).isZero();
        assertThat(exchangeRateRepository.count()).isEqualTo(1);

        ExchangeRate saved = exchangeRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD","KRW")
                .orElseThrow();

        assertThat(saved.getRate()).isEqualByComparingTo("1400.250000");
        assertThat(saved.getMidRate()).isEqualByComparingTo("1398.500000");
        assertThat(saved.getRateAt().toInstant()).isEqualTo(rateAt.toInstant());
        assertThat(saved.getCollectedAt().toInstant()).isEqualTo(COLLECTED_AT.toInstant());
    }

    @Test
    @DisplayName("기간 조회는 rateAt을 오름차순으로 반환")
    void t2() {
        OffsetDateTime firstAt = OffsetDateTime.parse("2026-08-26T06:00:00Z");
        OffsetDateTime secondAt = OffsetDateTime.parse("2026-08-26T07:00:00Z");

        exchangeRateRepository.insertIgnoreDuplicate(
                "USD",
                "KRW",
                new BigDecimal("1401.000000"),
                new BigDecimal("1399.000000"),
                secondAt,
                COLLECTED_AT
        );

        exchangeRateRepository.insertIgnoreDuplicate(
                "USD",
                "KRW",
                new BigDecimal("1400.000000"),
                new BigDecimal("1398.000000"),
                firstAt,
                COLLECTED_AT
        );

        List<ExchangeRate> rows =
                exchangeRateRepository
                        .findByBaseCurrencyAndQuoteCurrencyAndRateAtGreaterThanEqualOrderByRateAtAsc(
                                "USD",
                                "KRW",
                                firstAt.minusHours(1)
                        );

        assertThat(rows.stream()
                .map(row-> row.getRateAt().toInstant()).toList())
                .containsExactly(firstAt.toInstant(), secondAt.toInstant());

        assertThat(rows)
                .extracting(ExchangeRate::getMidRate)
                .containsExactly(new BigDecimal("1398.000000"), new BigDecimal("1399.000000"));
    }

}
