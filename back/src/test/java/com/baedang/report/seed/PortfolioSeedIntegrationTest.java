package com.baedang.report.seed;

import com.baedang.global.config.JpaConfig;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.report.seed.PortfolioSeedService.SeedResult;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
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
        return new PortfolioSeedService(users, accounts, holdings, quotes, stocks, jdbc,
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
        // 시드는 시세를 쓰지 않고 읽는다 → 기존 quote_snapshot 을 미리 넣어 둔다(prevClose 포함).
        for (Stock s : stocks.findAll()) {
            boolean us = s.getMarketCountry() == MarketCountry.US;
            OffsetDateTime ts = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
            QuoteSnapshot q = new QuoteSnapshot(
                    s.getStockId(), new BigDecimal(us ? "150.0000" : "70000.0000"),
                    us ? "USD" : "KRW", ts, ts);
            q.applyReference(ts.toLocalDate().minusDays(1), new BigDecimal(us ? "148.0000" : "69000.0000"));
            quotes.save(q);
        }
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

            // cash_balance = initial_cash − Σ(체결 정산액 net). 현금은 체결에 저장한 정수 원 net 을
            // 합산해 차감한다(보유 원가는 정밀 유지 → 소수 원 누적 오차로 현금과 어긋나지 않게).
            BigDecimal net = jdbc.queryForObject("""
                    SELECT COALESCE(sum(e.net_amount_krw), 0) FROM trade_execution e
                    JOIN trade_order o ON o.order_id = e.order_id
                    WHERE o.account_id = ?
                    """, BigDecimal.class, account.getAccountId());
            assertThat(account.getCashBalance()).isEqualByComparingTo(INITIAL_CASH.subtract(net));
            assertThat(account.getCashBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        // 시드는 공용 quote_snapshot 을 쓰지/덮지 않는다(읽기만) — 개수 불변·prevClose 보존.
        assertThat(holdings.findAll()).isNotEmpty();
        assertThat(quotes.findAll()).hasSize((int) stocks.count()); // seedUniverse 가 넣은 수 그대로
        for (QuoteSnapshot q : quotes.findAll()) {
            assertThat(q.getPrevClose()).isNotNull(); // 덮어쓰기로 null 이 되지 않음
        }
    }

    @Test
    void 보유마다_백데이트_매수체결을_적재하고_보유와_정합한다() {
        seedUniverse();

        service().seed();

        long holdingRows = holdings.count();
        Integer orderRows = jdbc.queryForObject("SELECT count(*) FROM trade_order", Integer.class);
        Integer execRows = jdbc.queryForObject("SELECT count(*) FROM trade_execution", Integer.class);
        // 보유당 매수 1건 → 주문·체결 수 = 보유 수.
        assertThat(orderRows).isEqualTo((int) holdingRows);
        assertThat(execRows).isEqualTo((int) holdingRows);

        // 전부 FILLED·BUY·MARKET.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM trade_order WHERE NOT (side='BUY' AND order_type='MARKET' AND status='FILLED')",
                Integer.class)).isZero();

        // BUY 정산 규칙: net = gross, tax·sec = 0.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM trade_execution WHERE net_amount_krw <> gross_amount_krw OR tax_krw <> 0 OR sec_fee_usd <> 0",
                Integer.class)).isZero();

        // 체결 수량이 보유 수량과 일치(보유↔체결 정합).
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM holding h
                JOIN trade_order o ON o.account_id = h.account_id AND o.stock_id = h.stock_id
                JOIN trade_execution e ON e.order_id = o.order_id
                WHERE e.quantity <> h.quantity
                """, Integer.class)).isZero();

        // 체결 시각은 개설~현재(백데이트) 사이이고 종목마다 분산돼 창 내 구성이 변한다.
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM trade_execution WHERE executed_at > ?", Integer.class, nowUtc)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT executed_at) FROM trade_execution", Integer.class)).isGreaterThan(1);
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
