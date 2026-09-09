package com.baedang.stock.runner;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import com.baedang.global.clients.kis.KisProperties;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.service.StockFinancialSyncService;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

class StockFinancialLoadRunnerTest {

    private final StockFinancialSyncService syncService = mock(StockFinancialSyncService.class);

    @Test
    void runner_does_nothing_when_load_financials_is_false() {
        StockFinancialLoadRunner runner = new StockFinancialLoadRunner(syncService, properties(false));

        runner.run(new DefaultApplicationArguments());

        verify(syncService, never()).refreshRankedTargets(any());
    }

    @Test
    void runner_delegates_to_sync_service_with_manual_trigger_when_load_financials_is_true() {
        StockFinancialLoadRunner runner = new StockFinancialLoadRunner(syncService, properties(true));

        runner.run(new DefaultApplicationArguments());

        verify(syncService).refreshRankedTargets(SyncTrigger.MANUAL);
    }

    @Test
    void sync_service_failure_is_not_propagated_out_of_runner() {
        doThrow(new BusinessException(ErrorCode.KIS_API_ERROR, "수동 적재 실패"))
                .when(syncService).refreshRankedTargets(SyncTrigger.MANUAL);
        StockFinancialLoadRunner runner = new StockFinancialLoadRunner(syncService, properties(true));

        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }

    private static KisProperties properties(boolean loadFinancials) {
        return new KisProperties(
                true,
                URI.create("https://example.test"),
                "key",
                "secret",
                18,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofDays(7),
                Duration.ofDays(30),
                loadFinancials);
    }
}
