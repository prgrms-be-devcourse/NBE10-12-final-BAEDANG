package com.baedang.stock.scheduler;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baedang.stock.service.StockFinancialSyncService;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

@Component
public class StockFinancialCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockFinancialCollectionScheduler.class);

    private final StockFinancialSyncService syncService;

    public StockFinancialCollectionScheduler(StockFinancialSyncService syncService) {
        this.syncService = Objects.requireNonNull(syncService, "syncService");
    }

    @Scheduled(cron = "0 10 8 * * MON", zone = "Asia/Seoul")
    public void scheduleWeeklyRefresh() {
        try {
            syncService.refreshRankedTargets(SyncTrigger.SCHEDULED);
        } catch (Exception exception) {
            log.error("Failed to run scheduled KIS financial collection", exception);
        }
    }
}
