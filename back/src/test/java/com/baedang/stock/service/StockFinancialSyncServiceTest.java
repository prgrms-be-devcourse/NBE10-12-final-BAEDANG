package com.baedang.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.baedang.global.clients.kis.KisProperties;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.FinancialPeriodType;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.entity.StockFinancialSync;
import com.baedang.stock.port.StockFinancialInfoPort;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryClassification;
import com.baedang.stock.port.StockFinancialInfoPort.IndustryData;
import com.baedang.stock.port.StockFinancialInfoPort.PeriodData;
import com.baedang.stock.repository.StockFinancialSyncRepository;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.service.StockFinancialSyncService.BatchSummary;
import com.baedang.stock.service.StockFinancialSyncService.GroupStatus;
import com.baedang.stock.service.StockFinancialSyncService.SyncResult;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class StockFinancialSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final long STOCK_ID = 10L;
    private static final String SYMBOL = "005930";

    @Mock StockFinancialInfoPort port;
    @Mock StockFinancialSyncRepository syncRepository;
    @Mock StockRepository stockRepository;
    @Mock StockFinancialPersistenceService persistenceService;
    @Mock Stock stock;

    private SimpleMeterRegistry meterRegistry;
    private StockFinancialSyncService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = service(Optional.of(port));
        lenient().when(stock.getStockId()).thenReturn(STOCK_ID);
        lenient().when(stock.getSymbol()).thenReturn(SYMBOL);
        lenient().when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        lenient().when(stock.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);
    }

    @AfterEach
    void closeMeterRegistry() {
        meterRegistry.close();
    }

    static Stream<Arguments> unsupportedStocks() {
        return Stream.of(
                Arguments.of(MarketCountry.US, StockCategory.INDIVIDUAL, "AAPL"),
                Arguments.of(MarketCountry.KR, StockCategory.ETF, "069500"),
                Arguments.of(MarketCountry.KR, StockCategory.ETN, "500001"),
                Arguments.of(MarketCountry.KR, StockCategory.INDIVIDUAL, "12345A"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedStocks")
    void unsupported_stock_is_rejected_without_cache_or_KIS_calls(
            MarketCountry country, StockCategory category, String symbol) {
        lenient().when(stock.getMarketCountry()).thenReturn(country);
        lenient().when(stock.getStockCategory()).thenReturn(category);
        lenient().when(stock.getSymbol()).thenReturn(symbol);

        assertThatThrownBy(() -> service.ensureFresh(stock, SyncTrigger.ON_DEMAND))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FINANCIALS_NOT_SUPPORTED));

        verifyNoInteractions(syncRepository, port, persistenceService);
        assertThat(counter("on_demand", "skipped")).isEqualTo(1);
    }

    @Test
    void fresh_groups_do_not_call_KIS() {
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.of(sync(
                NOW.minus(Duration.ofDays(29)),
                NOW.minus(Duration.ofDays(6)),
                NOW.minus(Duration.ofDays(6)))));

        SyncResult result = service.ensureFresh(stock, SyncTrigger.ON_DEMAND);

        assertThat(result.industry().status()).isEqualTo(GroupStatus.FRESH);
        assertThat(result.annual().status()).isEqualTo(GroupStatus.FRESH);
        assertThat(result.quarterly().status()).isEqualTo(GroupStatus.FRESH);
        assertThat(result.stale()).isFalse();
        verifyNoInteractions(port, persistenceService);
        assertThat(counter("on_demand", "success")).isEqualTo(1);
    }

    @Test
    void exact_TTL_boundary_is_stale_and_only_that_group_is_refreshed() {
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.of(sync(
                NOW.minus(Duration.ofDays(30)),
                NOW.minus(Duration.ofDays(1)),
                NOW.minus(Duration.ofDays(1)))));
        when(port.fetchIndustry(SYMBOL)).thenReturn(industry());

        SyncResult result = service.ensureFresh(stock, SyncTrigger.ON_DEMAND);

        assertThat(result.industry().status()).isEqualTo(GroupStatus.UPDATED);
        assertThat(result.annual().status()).isEqualTo(GroupStatus.FRESH);
        assertThat(result.quarterly().status()).isEqualTo(GroupStatus.FRESH);
        verify(persistenceService).saveIndustry(STOCK_ID, industry(), NOW);
        verify(port, never()).fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL);
        verify(port, never()).fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY);
    }

    @Test
    void scheduled_refresh_forces_both_financial_groups_but_keeps_fresh_industry() {
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.of(sync(NOW, NOW, NOW)));
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL))
                .thenReturn(List.of(period("202512")));
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509")));

        SyncResult result = service.refresh(stock, SyncTrigger.SCHEDULED);

        assertThat(result.industry().status()).isEqualTo(GroupStatus.FRESH);
        assertThat(result.annual().status()).isEqualTo(GroupStatus.UPDATED);
        assertThat(result.quarterly().status()).isEqualTo(GroupStatus.UPDATED);
        verify(port, never()).fetchIndustry(SYMBOL);
        verify(persistenceService).saveFinancials(
                STOCK_ID, FinancialPeriodType.ANNUAL, List.of(period("202512")), NOW);
        verify(persistenceService).saveFinancials(
                STOCK_ID, FinancialPeriodType.QUARTERLY, List.of(period("202509")), NOW);
    }

    @Test
    void one_group_failure_does_not_block_other_group_persistence() {
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.empty());
        when(port.fetchIndustry(SYMBOL)).thenReturn(industry());
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL))
                .thenThrow(new BusinessException(ErrorCode.KIS_RATE_LIMITED));
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509")));

        SyncResult result = service.ensureFresh(stock, SyncTrigger.ON_DEMAND);

        assertThat(result.industry().status()).isEqualTo(GroupStatus.UPDATED);
        assertThat(result.annual().status()).isEqualTo(GroupStatus.FAILED);
        assertThat(result.annual().errorCode()).isEqualTo(ErrorCode.KIS_RATE_LIMITED);
        assertThat(result.quarterly().status()).isEqualTo(GroupStatus.UPDATED);
        assertThat(result.stale()).isTrue();
        verify(persistenceService).saveIndustry(STOCK_ID, industry(), NOW);
        verify(persistenceService, never()).saveFinancials(
                eq(STOCK_ID), eq(FinancialPeriodType.ANNUAL), anyList(), eq(NOW));
        verify(persistenceService).saveFinancials(
                STOCK_ID, FinancialPeriodType.QUARTERLY, List.of(period("202509")), NOW);
        assertThat(counter("on_demand", "error")).isEqualTo(1);
    }

    @Test
    void provider_failure_log_includes_stock_and_safe_endpoint_context(CapturedOutput output) {
        String providerContext = "endpoint=BALANCE_SHEET trId=FHKST66430100 msgCd=ERR001";
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.empty());
        when(port.fetchIndustry(SYMBOL)).thenReturn(industry());
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL))
                .thenThrow(new BusinessException(ErrorCode.KIS_API_ERROR, providerContext));
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509")));

        service.ensureFresh(stock, SyncTrigger.ON_DEMAND);

        assertThat(output)
                .contains("stockId=10")
                .contains("symbol=005930")
                .contains("group=annual")
                .contains(providerContext);
    }

    @Test
    void normal_empty_responses_are_persisted_as_a_fresh_negative_cache() {
        IndustryData emptyIndustry = new IndustryData(null, null, null, null);
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.empty());
        when(port.fetchIndustry(SYMBOL)).thenReturn(emptyIndustry);
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL)).thenReturn(List.of());
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY)).thenReturn(List.of());

        SyncResult result = service.ensureFresh(stock, SyncTrigger.ON_DEMAND);

        assertThat(result.empty()).isTrue();
        assertThat(result.stale()).isFalse();
        verify(persistenceService).saveIndustry(STOCK_ID, emptyIndustry, NOW);
        verify(persistenceService).saveFinancials(
                STOCK_ID, FinancialPeriodType.ANNUAL, List.of(), NOW);
        verify(persistenceService).saveFinancials(
                STOCK_ID, FinancialPeriodType.QUARTERLY, List.of(), NOW);
        assertThat(counter("on_demand", "empty")).isEqualTo(1);
    }

    @Test
    void stale_cache_without_a_KIS_port_reports_unavailable() {
        service = service(Optional.empty());
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureFresh(stock, SyncTrigger.ON_DEMAND))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.KIS_API_UNAVAILABLE));

        verifyNoInteractions(persistenceService);
    }

    @Test
    void waiter_receives_the_owners_original_unavailable_error() throws Exception {
        service = service(Optional.empty());
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        when(syncRepository.findById(STOCK_ID)).thenAnswer(invocation -> {
            ownerEntered.countDown();
            if (!releaseOwner.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release owner");
            }
            return Optional.empty();
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SyncResult> owner = executor.submit(
                    () -> service.ensureFresh(stock, SyncTrigger.ON_DEMAND));
            assertThat(ownerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Thread> waiterThread = new AtomicReference<>();
            CountDownLatch waiterStarted = new CountDownLatch(1);
            Future<SyncResult> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                waiterStarted.countDown();
                return service.ensureFresh(stock, SyncTrigger.ON_DEMAND);
            });
            assertThat(waiterStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitWaiting(waiterThread.get());
            releaseOwner.countDown();

            assertUnavailable(owner);
            assertUnavailable(waiter);
            verify(syncRepository, times(1)).findById(STOCK_ID);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrent_requests_share_one_sync_and_completed_entry_is_removed() throws Exception {
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.empty());
        when(port.fetchIndustry(SYMBOL)).thenReturn(industry());
        CountDownLatch annualEntered = new CountDownLatch(1);
        CountDownLatch releaseAnnual = new CountDownLatch(1);
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL)).thenAnswer(invocation -> {
            annualEntered.countDown();
            if (!releaseAnnual.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release annual fetch");
            }
            return List.of(period("202512"));
        });
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SyncResult> first = executor.submit(
                    () -> service.ensureFresh(stock, SyncTrigger.ON_DEMAND));
            assertThat(annualEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<SyncResult> second = executor.submit(
                    () -> service.ensureFresh(stock, SyncTrigger.ON_DEMAND));
            releaseAnnual.countDown();

            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isSameAs(first.get(5, TimeUnit.SECONDS));

            service.ensureFresh(stock, SyncTrigger.ON_DEMAND);
            verify(port, times(2)).fetchIndustry(SYMBOL);
            verify(port, times(2)).fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL);
            verify(port, times(2)).fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void forced_refresh_waiting_for_nonforced_flight_runs_after_owner_completes() throws Exception {
        StockFinancialSync fresh = sync(NOW, NOW, NOW);
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        when(syncRepository.findById(STOCK_ID)).thenAnswer(invocation -> {
            if (ownerEntered.getCount() > 0) {
                ownerEntered.countDown();
                if (!releaseOwner.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release owner");
                }
            }
            return Optional.of(fresh);
        });
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL))
                .thenReturn(List.of(period("202512")));
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SyncResult> owner = executor.submit(
                    () -> service.ensureFresh(stock, SyncTrigger.ON_DEMAND));
            assertThat(ownerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Thread> forcedThread = new AtomicReference<>();
            CountDownLatch forcedStarted = new CountDownLatch(1);
            Future<SyncResult> forced = executor.submit(() -> {
                forcedThread.set(Thread.currentThread());
                forcedStarted.countDown();
                return service.refresh(stock, SyncTrigger.SCHEDULED);
            });
            assertThat(forcedStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitWaiting(forcedThread.get());
            releaseOwner.countDown();

            assertThat(owner.get(5, TimeUnit.SECONDS).annual().status())
                    .isEqualTo(GroupStatus.FRESH);
            assertThat(forced.get(5, TimeUnit.SECONDS).annual().status())
                    .isEqualTo(GroupStatus.UPDATED);
            verify(port).fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL);
            verify(port).fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failed_single_flight_entry_is_removed_so_the_next_call_can_retry() {
        when(syncRepository.findById(STOCK_ID)).thenReturn(Optional.empty());
        when(port.fetchIndustry(SYMBOL)).thenReturn(industry());
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL))
                .thenThrow(new IllegalStateException("provider body must not escape"))
                .thenReturn(List.of(period("202512")));
        when(port.fetchFinancials(SYMBOL, FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509")));

        SyncResult first = service.ensureFresh(stock, SyncTrigger.ON_DEMAND);
        SyncResult second = service.ensureFresh(stock, SyncTrigger.ON_DEMAND);

        assertThat(first.annual().status()).isEqualTo(GroupStatus.FAILED);
        assertThat(first.annual().errorCode()).isEqualTo(ErrorCode.KIS_API_ERROR);
        assertThat(second.annual().status()).isEqualTo(GroupStatus.UPDATED);
        verify(port, times(2)).fetchFinancials(SYMBOL, FinancialPeriodType.ANNUAL);
    }

    @Test
    void refreshRankedTargets_processes_all_targets_in_order_and_records_batch_metrics() {
        Stock first = mock(Stock.class);
        Stock second = mock(Stock.class);
        Stock third = mock(Stock.class);

        lenient().when(first.getStockId()).thenReturn(101L);
        lenient().when(first.getSymbol()).thenReturn("005930");
        lenient().when(first.getMarketCountry()).thenReturn(MarketCountry.KR);
        lenient().when(first.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);

        lenient().when(second.getStockId()).thenReturn(102L);
        lenient().when(second.getSymbol()).thenReturn("000660");
        lenient().when(second.getMarketCountry()).thenReturn(MarketCountry.KR);
        lenient().when(second.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);

        lenient().when(third.getStockId()).thenReturn(103L);
        lenient().when(third.getSymbol()).thenReturn("035420");
        lenient().when(third.getMarketCountry()).thenReturn(MarketCountry.KR);
        lenient().when(third.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);

        when(stockRepository.findKisFinancialCollectionTargets())
                .thenReturn(List.of(first, second, third));

        when(syncRepository.findById(101L)).thenReturn(Optional.of(sync(NOW, NOW, NOW)));
        when(port.fetchFinancials("005930", FinancialPeriodType.ANNUAL))
                .thenReturn(List.of(period("202512")));
        when(port.fetchFinancials("005930", FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509")));

        when(syncRepository.findById(102L)).thenReturn(Optional.of(sync(NOW, NOW, NOW)));
        when(port.fetchFinancials("000660", FinancialPeriodType.ANNUAL)).thenReturn(List.of());
        when(port.fetchFinancials("000660", FinancialPeriodType.QUARTERLY)).thenReturn(List.of());

        when(syncRepository.findById(103L)).thenReturn(Optional.of(sync(NOW, NOW, NOW)));
        when(port.fetchFinancials("035420", FinancialPeriodType.ANNUAL))
                .thenThrow(new BusinessException(ErrorCode.KIS_RATE_LIMITED));
        when(port.fetchFinancials("035420", FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509")));

        BatchSummary summary = service.refreshRankedTargets(SyncTrigger.SCHEDULED);

        assertThat(summary.totalTargets()).isEqualTo(3);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.emptyCount()).isEqualTo(1);
        assertThat(summary.failureCount()).isEqualTo(1);
        assertThat(summary.skippedCount()).isEqualTo(0);
        assertThat(meterRegistry.get("kis.financial.batch.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void refreshRankedTargets_continues_processing_when_a_target_throws_unexpected_exception() {
        Stock failing = mock(Stock.class);
        Stock successful = mock(Stock.class);

        lenient().when(failing.getStockId()).thenReturn(201L);
        lenient().when(failing.getSymbol()).thenReturn("005930");
        lenient().when(failing.getMarketCountry()).thenReturn(MarketCountry.KR);
        lenient().when(failing.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);

        lenient().when(successful.getStockId()).thenReturn(202L);
        lenient().when(successful.getSymbol()).thenReturn("000660");
        lenient().when(successful.getMarketCountry()).thenReturn(MarketCountry.KR);
        lenient().when(successful.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);

        when(stockRepository.findKisFinancialCollectionTargets())
                .thenReturn(List.of(failing, successful));

        when(syncRepository.findById(201L)).thenThrow(new IllegalStateException("db connectivity issue"));

        when(syncRepository.findById(202L)).thenReturn(Optional.of(sync(NOW, NOW, NOW)));
        when(port.fetchFinancials("000660", FinancialPeriodType.ANNUAL))
                .thenReturn(List.of(period("202512")));
        when(port.fetchFinancials("000660", FinancialPeriodType.QUARTERLY))
                .thenReturn(List.of(period("202509")));

        BatchSummary summary = service.refreshRankedTargets(SyncTrigger.SCHEDULED);

        assertThat(summary.totalTargets()).isEqualTo(2);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.failureCount()).isEqualTo(1);
        verify(port).fetchFinancials("000660", FinancialPeriodType.ANNUAL);
    }

    private StockFinancialSyncService service(Optional<StockFinancialInfoPort> optionalPort) {
        return new StockFinancialSyncService(
                stockRepository,
                optionalPort,
                syncRepository,
                persistenceService,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry);
    }

    private static KisProperties properties() {
        return new KisProperties(
                true,
                URI.create("https://example.test"),
                "test-key",
                "test-secret",
                18,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofDays(7),
                Duration.ofDays(30),
                false);
    }

    private static StockFinancialSync sync(
            Instant industryAt, Instant annualAt, Instant quarterlyAt) {
        StockFinancialSync sync = StockFinancialSync.create(STOCK_ID);
        if (industryAt != null) sync.markIndustrySynced(industryAt);
        if (annualAt != null) sync.markFinancialSynced(FinancialPeriodType.ANNUAL, annualAt);
        if (quarterlyAt != null) sync.markFinancialSynced(FinancialPeriodType.QUARTERLY, quarterlyAt);
        return sync;
    }

    private static IndustryData industry() {
        IndustryClassification classification = new IndustryClassification("03", "제조업");
        return new IndustryData(classification, classification, classification, classification);
    }

    private static PeriodData period(String statementYearMonth) {
        return new PeriodData(statementYearMonth, null, null, null);
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
    }

    private static void assertUnavailable(Future<SyncResult> future) {
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(ExecutionException.class, exception ->
                        assertThat(exception.getCause())
                                .isInstanceOfSatisfying(BusinessException.class, business ->
                                        assertThat(business.getErrorCode())
                                                .isEqualTo(ErrorCode.KIS_API_UNAVAILABLE)));
    }

    private double counter(String trigger, String result) {
        return meterRegistry.get("kis.financial.sync")
                .tags("trigger", trigger, "result", result)
                .counter()
                .count();
    }
}
