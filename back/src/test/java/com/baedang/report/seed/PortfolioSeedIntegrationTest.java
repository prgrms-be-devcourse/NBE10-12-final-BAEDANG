package com.baedang.report.seed;

import com.baedang.global.config.JpaConfig;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.report.seed.PortfolioSeedService.SeedResult;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class) // BaseEntity @CreatedDate/@LastModifiedDate 활성화(없으면 created_at null)
class PortfolioSeedIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    private static final BigDecimal INITIAL_CASH = new BigDecimal("50000000");
    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private static final int ACCOUNT_COUNT = 5;

    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired HoldingRepository holdings;
    @Autowired QuoteSnapshotRepository quotes;
    @Autowired StockRepository stocks;
    @Autowired JdbcTemplate jdbc;

    private PortfolioSeedService service() {
        return new PortfolioSeedService(users, accounts, holdings, quotes, stocks,
                INITIAL_CASH, ACCOUNT_COUNT, 4, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seedUniverse() {
        // 개별주·ETF·레버리지를 섞은 랭킹 유니버스(KR/US). 유형은 emergent.
        insertRanked("005930", "KR", "KOSPI", "삼성전자", "KRW", "STOCK", "INDIVIDUAL", null, "9000000000000");
        insertRanked("069500", "KR", "KOSPI", "KODEX 200", "KRW", "ETF", "ETF", "1.0", "3000000000000");
        insertRanked("122630", "KR", "KOSPI", "KODEX 레버리지", "KRW", "ETF", "ETF", "2.0", "2000000000000");
        insertRanked("NVDA", "US", "NASDAQ", "엔비디아", "USD", "STOCK", "INDIVIDUAL", null, "8000000000000");
        insertRanked("SPY", "US", "NYSE", "SPDR S&P500", "USD", "ETF", "ETF", "1.0", "5000000000000");
        insertRanked("TSLA", "US", "NASDAQ", "테슬라", "USD", "STOCK", "INDIVIDUAL", null, "4000000000000");
    }

    private void insertRanked(String symbol, String country, String market, String name, String currency,
                              String securityType, String category, String leverage, String tradingAmount) {
        jdbc.update("""
                INSERT INTO stock(symbol, market_country, market, name, currency, security_type,
                        stock_category, is_ranked, leverage_factor, trading_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, true, ?, ?::numeric)
                """, symbol, country, market, name, currency, securityType, category,
                leverage == null ? null : new BigDecimal(leverage), tradingAmount);
    }

    @Test
    void 유니버스가_없으면_아무것도_적재하지_않는다() {
        SeedResult result = service().seed();
        assertThat(result.accounts()).isZero();
        assertThat(users.countBySeedTrue()).isZero();
    }

    @Test
    void 계좌_보유_시세를_적재하고_현금_불변식을_지킨다() {
        seedUniverse();

        SeedResult result = service().seed();

        assertThat(result.accounts()).isEqualTo(ACCOUNT_COUNT);
        assertThat(result.holdings()).isPositive();
        assertThat(result.skipped()).isZero();
        assertThat(users.countBySeedTrue()).isEqualTo(ACCOUNT_COUNT);

        List<Account> seeded = accounts.findAll();
        assertThat(seeded).hasSize(ACCOUNT_COUNT);
        for (Account account : seeded) {
            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            // 개설 4주 이전(리포트/리더보드 게이트 통과).
            assertThat(account.getOpenedAt()).isBefore(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusWeeks(4));

            // cash_balance = initial_cash − Σ(보유 원가). 도메인 팩토리가 강제한 불변식.
            BigDecimal cost = holdings.findByAccountIdAndQuantityGreaterThan(account.getAccountId(), BigDecimal.ZERO)
                    .stream().map(Holding::getKrwPurchaseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(account.getCashBalance()).isEqualByComparingTo(INITIAL_CASH.subtract(cost));
            assertThat(account.getCashBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        // 보유한 종목엔 return% 계산용 시세가 있어야 한다.
        List<Holding> allHoldings = holdings.findAll();
        assertThat(allHoldings).isNotEmpty();
        for (Holding holding : allHoldings) {
            assertThat(quotes.findById(holding.getStockId())).isPresent();
        }
    }

    @Test
    void 이미_시드가_있으면_멱등하게_건너뛴다() {
        seedUniverse();
        service().seed();

        SeedResult again = service().seed();

        assertThat(again.accounts()).isZero();
        assertThat(again.skipped()).isEqualTo(ACCOUNT_COUNT);
        assertThat(users.countBySeedTrue()).isEqualTo(ACCOUNT_COUNT);
    }
}
