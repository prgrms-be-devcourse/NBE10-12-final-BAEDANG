package com.baedang.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.dto.StockFinancialResponse;
import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockFinancialPeriod;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockFinancialInfoPort;
import com.baedang.stock.port.StockFinancialInfoPort.BalanceSheet;
import com.baedang.stock.port.StockFinancialInfoPort.IncomeStatement;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryClassification;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryData;
import com.baedang.stock.port.StockFinancialInfoPort.PeriodData;
import com.baedang.stock.port.StockFinancialInfoPort.Ratios;
import com.baedang.stock.repository.StockFinancialPeriodRepository;
import com.baedang.stock.repository.StockFinancialSyncRepository;
import com.baedang.stock.repository.StockIndustryRepository;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "kis.enabled=true",
        "kis.base-url=https://example.test",
        "kis.app-key=test-app-key",
        "kis.app-secret=test-app-secret",
        "logging.level.org.hibernate.SQL=OFF"
})
class StockFinancialFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    @MockitoBean MarketCalendarPort marketCalendarPort;
    @MockitoBean StockFinancialInfoPort port;

    @Autowired StockFinancialSyncService syncService;
    @Autowired StockFinancialQueryService queryService;
    @Autowired StockFinancialPersistenceService persistenceService;
    @Autowired StockRepository stockRepository;
    @Autowired StockIndustryRepository industryRepository;
    @MockitoSpyBean StockFinancialPeriodRepository periodRepository;
    @Autowired StockFinancialSyncRepository syncRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM stock_financial_period");
        jdbcTemplate.execute("DELETE FROM stock_financial_sync");
        jdbcTemplate.execute("DELETE FROM stock_industry");
        jdbcTemplate.execute("DELETE FROM stock");
    }

    @Test
    void external_calls_execute_without_an_active_transaction() {
        Stock stock = stockRepository.save(Stock.create(
                "005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true));

        List<Boolean> txActiveDuringFetch = Collections.synchronizedList(new ArrayList<>());

        when(port.fetchIndustry(stock.getSymbol())).thenAnswer(invocation -> {
            txActiveDuringFetch.add(TransactionSynchronizationManager.isActualTransactionActive());
            return industry("0326");
        });
        when(port.fetchFinancials(eq(stock.getSymbol()), any())).thenAnswer(invocation -> {
            txActiveDuringFetch.add(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(period("202512", "5000"));
        });

        syncService.ensureFresh(stock, SyncTrigger.ON_DEMAND);

        assertThat(txActiveDuringFetch)
                .hasSize(3)
                .containsOnly(false);

        assertThat(industryRepository.findById(stock.getStockId())).isPresent();
        assertThat(periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL)).hasSize(1);
        assertThat(syncRepository.findById(stock.getStockId())).isPresent();
    }

    @Test
    void failed_period_group_does_not_corrupt_existing_rows_while_independent_group_succeeds() {
        Stock stock = stockRepository.save(Stock.create(
                "000660", MarketCountry.KR, "KOSPI", "SK하이닉스", null, "KRW", "STOCK", true));
        Instant oldSyncedAt = Instant.parse("2026-08-01T00:00:00Z");

        persistenceService.saveIndustry(stock.getStockId(), industry("0326"), oldSyncedAt);
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(period("202412", "1000")), oldSyncedAt);
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.QUARTERLY,
                List.of(period("202409", "800")), oldSyncedAt);

        when(port.fetchIndustry(stock.getSymbol())).thenReturn(industry("0326"));
        when(port.fetchFinancials(stock.getSymbol(), FinancialPeriodType.ANNUAL))
                .thenThrow(new BusinessException(ErrorCode.KIS_RATE_LIMITED));
        when(port.fetchFinancials(stock.getSymbol(), FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509", "950")));

        syncService.ensureFresh(stock, SyncTrigger.ON_DEMAND);

        var annualPeriods = periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL);
        assertThat(annualPeriods).hasSize(1);
        assertThat(annualPeriods.getFirst().getSales()).isEqualByComparingTo("1000");

        var sync = syncRepository.findById(stock.getStockId()).orElseThrow();
        assertThat(sync.getAnnualSyncedAt().toInstant()).isEqualTo(oldSyncedAt);

        var quarterlyPeriods = periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.QUARTERLY);
        assertThat(quarterlyPeriods).hasSize(2);
        assertThat(quarterlyPeriods.getFirst().getStatementYearMonth()).isEqualTo("202509");
        assertThat(quarterlyPeriods.getFirst().getSales()).isEqualByComparingTo("950");
        assertThat(quarterlyPeriods.get(1).getStatementYearMonth()).isEqualTo("202409");
        assertThat(quarterlyPeriods.get(1).getSales()).isEqualByComparingTo("800");
        assertThat(sync.getQuarterlySyncedAt().toInstant()).isAfter(oldSyncedAt);

        StockFinancialResponse response = queryService.getFinancials(stock.getSymbol(), "KR");
        assertThat(response.dataStatus()).isEqualTo("STALE");
        assertThat(response.annual().getFirst().incomeStatement().sales()).isEqualTo("1000");
        assertThat(response.quarterly().getFirst().incomeStatement().sales()).isEqualTo("950");
    }

    @Test
    void negative_cache_and_corrections_preserve_past_rows_and_update_values() {
        Stock stock = stockRepository.save(Stock.create(
                "035420", MarketCountry.KR, "KOSPI", "NAVER", null, "KRW", "STOCK", true));

        when(port.fetchIndustry(stock.getSymbol())).thenReturn(new IndustryData(null, null, null, null));
        when(port.fetchFinancials(stock.getSymbol(), FinancialPeriodType.ANNUAL)).thenReturn(List.of());
        when(port.fetchFinancials(stock.getSymbol(), FinancialPeriodType.QUARTERLY)).thenReturn(List.of());

        StockFinancialResponse firstResponse = queryService.getFinancials(stock.getSymbol(), "KR");
        assertThat(firstResponse.dataStatus()).isEqualTo("FRESH");
        assertThat(firstResponse.annual()).isEmpty();
        assertThat(firstResponse.quarterly()).isEmpty();
        assertThat(firstResponse.syncedAt().annual()).isNotNull();

        StockFinancialResponse secondResponse = queryService.getFinancials(stock.getSymbol(), "KR");
        assertThat(secondResponse.dataStatus()).isEqualTo("FRESH");
        verify(port, times(1)).fetchIndustry(stock.getSymbol());
        verify(port, times(1)).fetchFinancials(stock.getSymbol(), FinancialPeriodType.ANNUAL);
        verify(port, times(1)).fetchFinancials(stock.getSymbol(), FinancialPeriodType.QUARTERLY);

        Instant baseTime = Instant.now();
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(period("202412", "1000"), period("202312", "900")), baseTime);

        when(port.fetchFinancials(stock.getSymbol(), FinancialPeriodType.ANNUAL))
                .thenReturn(List.of(period("202412", "1200")));

        syncService.refresh(stock, SyncTrigger.ON_DEMAND);

        var periods = periodRepository.findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL);

        assertThat(periods)
                .extracting(p -> p.getStatementYearMonth())
                .containsExactly("202412", "202312");
        assertThat(periods)
                .extracting(p -> p.getSales().stripTrailingZeros().toPlainString())
                .containsExactly("1200", "900");
    }

    @Test
    void response_assembly_uses_one_database_snapshot() throws Exception {
        Stock stock = stockRepository.save(Stock.create(
                "051910", MarketCountry.KR, "KOSPI", "LG화학", null, "KRW", "STOCK", true));
        Instant oldSyncedAt = Instant.now();
        Instant newSyncedAt = oldSyncedAt.plusSeconds(60);
        persistenceService.saveIndustry(stock.getStockId(), industry("0200"), oldSyncedAt);
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                List.of(period("202412", "1000")), oldSyncedAt);
        persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.QUARTERLY,
                List.of(period("202409", "800")), oldSyncedAt);

        CountDownLatch annualRead = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicBoolean blocked = new AtomicBoolean();
        doAnswer(invocation -> {
            List<StockFinancialPeriod> result = periodRepository.findAll().stream()
                    .filter(period -> period.getStockId().equals(stock.getStockId()))
                    .filter(period -> period.getPeriodType() == FinancialPeriodType.ANNUAL)
                    .toList();
            if (blocked.compareAndSet(false, true)) {
                annualRead.countDown();
                if (!releaseReader.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release snapshot reader");
                }
            }
            return result;
        }).when(periodRepository).findByStockIdAndPeriodTypeOrderByStatementYearMonthDesc(
                stock.getStockId(), FinancialPeriodType.ANNUAL);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var responseFuture = executor.submit(() -> queryService.getFinancials(stock.getSymbol(), "KR"));
            if (!annualRead.await(5, TimeUnit.SECONDS)) {
                responseFuture.get(1, TimeUnit.SECONDS);
                throw new AssertionError("financial response completed before the annual snapshot read was observed");
            }

            persistenceService.saveFinancials(stock.getStockId(), FinancialPeriodType.ANNUAL,
                    List.of(period("202412", "2000")), newSyncedAt);
            releaseReader.countDown();

            StockFinancialResponse response = responseFuture.get(5, TimeUnit.SECONDS);
            assertThat(response.annual().getFirst().incomeStatement().sales()).isEqualTo("1000");
            assertThat(response.syncedAt().annual().toInstant()).isEqualTo(oldSyncedAt);
        } finally {
            releaseReader.countDown();
        }

        assertThat(syncRepository.findById(stock.getStockId()).orElseThrow()
                .getAnnualSyncedAt().toInstant()).isEqualTo(newSyncedAt);
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
