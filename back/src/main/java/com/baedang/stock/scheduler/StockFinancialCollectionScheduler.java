package com.baedang.stock.scheduler;

import java.util.Objects;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.baedang.stock.service.StockFinancialSyncService;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

@Component
@ConditionalOnProperty(prefix = "kis", name = "enabled", havingValue = "true")
public class StockFinancialCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockFinancialCollectionScheduler.class);

    private final StockFinancialSyncService syncService;
    private final Executor executor;

    public StockFinancialCollectionScheduler(
            StockFinancialSyncService syncService,
            @Qualifier("stockFinancialTaskExecutor") Executor executor) {
        this.syncService = Objects.requireNonNull(syncService, "syncService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Scheduled(cron = "0 10 8 * * MON", zone = "Asia/Seoul")
    public void scheduleWeeklyRefresh() {
        try {
            executor.execute(this::refresh);
        } catch (RuntimeException exception) {
            log.error("Failed to submit scheduled KIS financial collection", exception);
        }
    }

    private void refresh() {
        try {
            syncService.refreshRankedTargets(SyncTrigger.SCHEDULED);
        } catch (Exception exception) {
            log.error("Failed to run scheduled KIS financial collection", exception);
        }
    }
}
