package com.baedang.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockFinancialInfoPort.BalanceSheet;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryClassification;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryData;
import com.baedang.stock.port.StockFinancialInfoPort.IncomeStatement;
import com.baedang.stock.port.StockFinancialInfoPort.PeriodData;
import com.baedang.stock.port.StockFinancialInfoPort.Ratios;
import com.baedang.stock.repository.StockFinancialPeriodRepository;
import com.baedang.stock.repository.StockFinancialSyncRepository;
import com.baedang.stock.repository.StockIndustryRepository;
import com.baedang.stock.repository.StockRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@Transactional
class StockFinancialPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired StockFinancialPersistenceService persistenceService;
    @Autowired StockRepository stockRepository;
    @Autowired StockIndustryRepository industryRepository;
    @Autowired StockFinancialPeriodRepository periodRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @PersistenceContext EntityManager entityManager;
    @Autowired StockFinancialSyncRepository syncRepository;
    @MockitoBean MarketCalendarPort marketCalendarPort;

    @Test
    void industry_upsert_and_independent_period_sync_are_persisted() {
        Stock stock = stockRepository.save(Stock.create(
                "005930", MarketCountry.KR, "KOSPI", "삼성전자", null,
                "KRW", "STOCK", true));
        Instant industryAt = Instant.parse("2026-09-08T00:00:00Z");
        Instant annualAt = Instant.parse("2026-09-08T00:01:00Z");
        Instant quarterlyAt = Instant.parse("2026-09-08T00:02:00Z");

        persistenceService.saveIndustry(stock.getStockId(), industry("STD-1"), industryAt);
        persistenceService.saveIndustry(stock.getStockId(), industry("STD-2"), industryAt.plusSeconds(1));
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(period("202512", "100"), period("202412", "90")), annualAt);
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.QUARTERLY,
                List.of(period("202512", "110")), quarterlyAt);
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(period("202512", "120")), annualAt.plusSeconds(1));

        assertThat(industryRepository.findById(stock.getStockId()).orElseThrow().getStandardIndustryCode())
                .isEqualTo("STD-2");
        assertThat(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL))
                .extracting(period -> period.getStatementYearMonth())
                .containsExactly("202512", "202412");
        assertThat(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL))
                .extracting(period -> period.getSales().stripTrailingZeros().toPlainString())
                .containsExactly("120", "90");
        assertThat(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.QUARTERLY))
                .hasSize(1);
        assertThat(syncRepository.findById(stock.getStockId()).orElseThrow().getIndustrySyncedAt().toInstant())
                .isEqualTo(industryAt.plusSeconds(1));
        assertThat(syncRepository.findById(stock.getStockId()).orElseThrow().getAnnualSyncedAt().toInstant())
                .isEqualTo(annualAt.plusSeconds(1));
        assertThat(syncRepository.findById(stock.getStockId()).orElseThrow().getQuarterlySyncedAt().toInstant())
                .isEqualTo(quarterlyAt);
    }

    @Test
    void stock_delete_cascades_financial_rows() {
        Stock stock = stockRepository.save(Stock.create(
                "000660", MarketCountry.KR, "KOSPI", "SK하이닉스", null,
                "KRW", "STOCK", true));
        persistenceService.saveIndustry(stock.getStockId(), industry("STD"), Instant.now());
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(period("202512", "100")), Instant.now());
        stockRepository.delete(stock);
        stockRepository.flush();
        entityManager.clear();

        assertThat(industryRepository.findById(stock.getStockId())).isEmpty();
        assertThat(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL)).isEmpty();
        assertThat(syncRepository.findById(stock.getStockId())).isEmpty();
    }

    @Test
    void returned_sources_replace_nulls_while_absent_sources_preserve_existing_values() {
        Stock stock = stockRepository.save(Stock.create(
                "035420", MarketCountry.KR, "KOSPI", "NAVER", null,
                "KRW", "STOCK", true));
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(new PeriodData("202512",
                        new BalanceSheet(new BigDecimal("100"), null, null, null, null,
                                null, null, null, null, null),
                        new IncomeStatement(new BigDecimal("200"), new BigDecimal("20"), null),
                        null)),
                Instant.parse("2026-09-08T00:00:00Z"));
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(new PeriodData("202512", null,
                        new IncomeStatement(new BigDecimal("300"), null, null), null)),
                Instant.parse("2026-09-08T00:01:00Z"));

        var period = periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL).getFirst();
        assertThat(period.getCurrentAssets()).isEqualByComparingTo("100");
        assertThat(period.getSales()).isEqualByComparingTo("300");
        assertThat(period.getOperatingProfit()).isNull();

        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(new PeriodData("202512",
                        new BalanceSheet(null, null, null, null, null, null, null, null, null, null),
                        null, null)),
                Instant.parse("2026-09-08T00:02:00Z"));
        assertThat(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL).getFirst().getCurrentAssets()).isNull();
    }

    @Test
    void financial_and_profit_ratio_sources_update_independently() {
        Stock stock = stockRepository.save(Stock.create(
                "207940", MarketCountry.KR, "KOSPI", "삼성바이오로직스", null,
                "KRW", "STOCK", true));
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(new PeriodData("202512", null, null,
                        new Ratios(null, null, null, new BigDecimal("5"), null,
                                null, null, null, null, new BigDecimal("10")))),
                Instant.parse("2026-09-08T00:00:00Z"));
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(new PeriodData("202512", null, null,
                        new Ratios(null, null, null, new BigDecimal("6"), null,
                                null, null, null, null, null), true, false)),
                Instant.parse("2026-09-08T00:01:00Z"));

        var afterFinancial = periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL).getFirst();
        assertThat(afterFinancial.getRoe()).isEqualByComparingTo("6");
        assertThat(afterFinancial.getNetProfitMargin()).isEqualByComparingTo("10");

        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(new PeriodData("202512", null, null,
                        new Ratios(null, null, null, null, null,
                                null, null, null, null, new BigDecimal("20")), false, true)),
                Instant.parse("2026-09-08T00:02:00Z"));
        var afterProfit = periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL).getFirst();
        assertThat(afterProfit.getRoe()).isEqualByComparingTo("6");
        assertThat(afterProfit.getNetProfitMargin()).isEqualByComparingTo("20");
    }

    @Test
    void empty_financial_response_still_updates_the_period_sync_time() {
        Stock stock = stockRepository.save(Stock.create(
                "051910", MarketCountry.KR, "KOSPI", "LG화학", null,
                "KRW", "STOCK", true));
        Instant syncedAt = Instant.parse("2026-09-08T00:02:00Z");

        persistenceService.saveFinancials(
                stock.getStockId(), FinancialPeriodType.ANNUAL, List.of(), syncedAt);

        assertThat(syncRepository.findById(stock.getStockId()).orElseThrow()
                .getAnnualSyncedAt().toInstant()).isEqualTo(syncedAt);
    }

    @Test
    void invalid_statement_month_is_rejected_by_the_database() {
        Stock stock = stockRepository.save(Stock.create(
                "006400", MarketCountry.KR, "KOSPI", "삼성SDI", null,
                "KRW", "STOCK", true));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO stock_financial_period (stock_id, period_type, statement_year_month)
                VALUES (?, 'ANNUAL', '20251')
                """, stock.getStockId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unsupported_period_type_is_rejected_by_the_database() {
        Stock stock = stockRepository.save(Stock.create(
                "068270", MarketCountry.KR, "KOSPI", "셀트리온", null,
                "KRW", "STOCK", true));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO stock_financial_period (stock_id, period_type, statement_year_month)
                VALUES (?, 'MONTHLY', '202512')
                """, stock.getStockId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void entity_factories_reject_missing_or_invalid_keys() {
        assertThatThrownBy(() -> com.baedang.stock.entity.StockFinancialPeriod.create(
                null, FinancialPeriodType.ANNUAL, "202512"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> com.baedang.stock.entity.StockFinancialPeriod.create(
                1L, null, "202512"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> com.baedang.stock.entity.StockFinancialPeriod.create(
                1L, FinancialPeriodType.ANNUAL, "20251"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> com.baedang.stock.entity.StockFinancialSync.create(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> com.baedang.stock.entity.StockIndustry.create(
                null, industry("STD"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodData(
                "202512", null, null, null, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findKisFinancialCollectionTargets_returns_only_ranked_KR_non_etf_etn_stocks() {
        Stock krRanked1 = stockRepository.save(Stock.create(
                "005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true));
        krRanked1.applyRanking(2, new BigDecimal("1000000"));

        Stock krRanked2 = stockRepository.save(Stock.create(
                "000660", MarketCountry.KR, "KOSPI", "SK하이닉스", null, "KRW", "STOCK", true));
        krRanked2.applyRanking(1, new BigDecimal("900000"));

        Stock krUnranked = stockRepository.save(Stock.create(
                "035420", MarketCountry.KR, "KOSPI", "NAVER", null, "KRW", "STOCK", true));

        Stock krEtf = stockRepository.save(Stock.create(
                "069500", MarketCountry.KR, "KOSPI", "KODEX 200", null, "KRW", "ETF", true));
        krEtf.applyRanking(3, new BigDecimal("800000"));

        Stock krEtn = stockRepository.save(Stock.create(
                "500001", MarketCountry.KR, "KOSPI", "신한 코스피 ETN", null, "KRW", "ETN", true));
        krEtn.applyRanking(4, new BigDecimal("700000"));

        Stock usRanked = stockRepository.save(Stock.create(
                "AAPL", MarketCountry.US, "NASDAQ", "Apple", null, "USD", "STOCK", true));
        usRanked.applyRanking(1, new BigDecimal("5000000"));

        List<Stock> targets = stockRepository.findKisFinancialCollectionTargets(PageRequest.of(0, 100));

        assertThat(targets)
                .extracting(Stock::getSymbol)
                .containsExactly(krRanked2.getSymbol(), krRanked1.getSymbol())
                .doesNotContain(krUnranked.getSymbol(), krEtf.getSymbol(), krEtn.getSymbol(), usRanked.getSymbol());
    }

    @Test
    void findKisFinancialCollectionTargets_limits_each_batch_to_top_100_ranks() {
        List<Stock> ranked = new ArrayList<>();
        for (int rank = 1; rank <= 101; rank++) {
            Stock stock = Stock.create(
                    String.valueOf(100000 + rank), MarketCountry.KR, "KOSPI", "종목 " + rank,
                    null, "KRW", "STOCK", true);
            stock.applyRanking(rank, BigDecimal.valueOf(1_000_000L - rank));
            ranked.add(stock);
        }
        stockRepository.saveAll(ranked);

        List<Stock> targets = stockRepository.findKisFinancialCollectionTargets(PageRequest.of(0, 100));

        assertThat(targets).hasSize(100);
        assertThat(targets.getFirst().getRankNo()).isEqualTo(1);
        assertThat(targets.getLast().getRankNo()).isEqualTo(100);
    }

    private static IndustryData industry(String code) {
        IndustryClassification classification = new IndustryClassification(code, "산업");
        return new IndustryData(classification, classification, classification, classification);
    }

    private static PeriodData period(String yearMonth, String sales) {
        return new PeriodData(
                yearMonth,
                new BalanceSheet(null, null, null, null, null, null, null, null, null, null),
                new IncomeStatement(new BigDecimal(sales), null, null),
                new Ratios(null, null, null, null, null, null, null, null, null, null));
    }
}
