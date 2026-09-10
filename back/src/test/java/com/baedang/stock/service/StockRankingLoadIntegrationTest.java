package com.baedang.stock.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.RankingEntry;
import com.baedang.stock.repository.StockRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Ranking aggregation must not overwrite verified market prices. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@Transactional
class StockRankingLoadIntegrationTest {

    private static final OffsetDateTime RANKED_AT =
            OffsetDateTime.of(2026, 9, 7, 8, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-07T08:00:05Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired StockRankingLoadService service;
    @Autowired StockRepository stockRepository;
    @Autowired QuoteSnapshotRepository quoteSnapshotRepository;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbcTemplate;

    /** collected_at 을 고정해 검증할 수 있도록 TimeConfig 의 Clock 을 대체한다. */
    @MockitoBean Clock clock;

    // 개발용 대역(Fake) 구현체가 없어졌으므로, 이 테스트가 관심 없는 MarketCalendarPort
    // 의존을 목(mock)으로 채워 넣어야 컨텍스트가 뜬다(다른 서비스가 직접 주입받는다).
    @MockitoBean MarketCalendarPort marketCalendarPort;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(NOW.toInstant());
    }

    @Test
    void 랭킹_집계시각과_가격으로_시세를_초기화하지_않는다() {
        Stock added = saveStock(MarketCountry.KR, false);
        service.applyRanking(MarketCountry.KR, List.of(entry(1, added.getSymbol())), RANKED_AT);
        flushAndClear();
        assertThat(quoteSnapshotRepository.findById(added.getStockId())).isEmpty();
        assertThat(stockRepository.findById(added.getStockId()).orElseThrow().getIsRanked()).isTrue();
    }

    @Test
    void 재편입_종목의_검증된_가격과_기준가를_보존한다() {
        Stock added = saveStock(MarketCountry.KR, false);
        saveSnapshot(added, "71000", "50000");
        service.applyRanking(MarketCountry.KR, List.of(entry(1, added.getSymbol())), RANKED_AT);
        flushAndClear();
        assertThat(lastPrice(added)).isEqualByComparingTo("71000");
        assertThat(prevClose(added)).isEqualByComparingTo("50000");
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private RankingEntry entry(int rank, String symbol) {
        return entry(rank, symbol, new BigDecimal("70000"), new BigDecimal("69000"));
    }

    private RankingEntry entry(
            int rank, String symbol, BigDecimal lastPrice, BigDecimal basePrice) {
        return new RankingEntry(
                rank,
                symbol,
                "KRW",
                lastPrice,
                basePrice,
                new BigDecimal("0.0145"),
                new BigDecimal("1000000"),
                new BigDecimal("70000000000")
        );
    }

    private Stock saveStock(MarketCountry marketCountry, boolean ranked) {
        String symbol = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Stock stock = Stock.create(
                symbol,
                marketCountry,
                marketCountry == MarketCountry.KR ? "KOSPI" : "NASDAQ",
                "테스트 종목",
                null,
                marketCountry == MarketCountry.KR ? "KRW" : "USD",
                "STOCK",
                true
        );
        if (ranked) stock.applyRanking(1, BigDecimal.TEN);
        return stockRepository.saveAndFlush(stock);
    }

    private void saveSnapshot(Stock stock, String lastPrice, String prevClose) {
        QuoteSnapshot snapshot = new QuoteSnapshot(
                stock.getStockId(),
                new BigDecimal(lastPrice),
                stock.getCurrency(),
                RANKED_AT,
                RANKED_AT
        );
        snapshot.applyReference(snapshot.getQuoteAt().toLocalDate().minusDays(1), new BigDecimal(prevClose));
        quoteSnapshotRepository.saveAndFlush(snapshot);
    }

    private BigDecimal prevClose(Stock stock) {
        return column(stock, "prev_close", BigDecimal.class);
    }

    private BigDecimal lastPrice(Stock stock) {
        return column(stock, "last_price", BigDecimal.class);
    }

    private OffsetDateTime collectedAt(Stock stock) {
        return column(stock, "collected_at", OffsetDateTime.class);
    }

    private String currency(Stock stock) {
        return column(stock, "currency", String.class);
    }

    private <T> T column(Stock stock, String columnName, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT " + columnName + " FROM quote_snapshot WHERE stock_id = ?",
                type,
                stock.getStockId()
        );
    }
}
