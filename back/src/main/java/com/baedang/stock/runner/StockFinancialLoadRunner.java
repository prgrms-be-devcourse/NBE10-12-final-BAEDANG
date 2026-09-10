package com.baedang.stock.runner;

import java.util.Objects;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.baedang.global.clients.kis.KisProperties;
import com.baedang.stock.service.StockFinancialSyncService;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

@Component
public class StockFinancialLoadRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockFinancialLoadRunner.class);

    private final StockFinancialSyncService syncService;
    private final KisProperties properties;
    private final Executor executor;

    public StockFinancialLoadRunner(
            StockFinancialSyncService syncService,
            KisProperties properties,
            @Qualifier("stockFinancialTaskExecutor") Executor executor) {
        this.syncService = Objects.requireNonNull(syncService, "syncService");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.loadFinancials()) {
            return;
        }
        try {
            executor.execute(this::refresh);
        } catch (RuntimeException exception) {
            log.error("Failed to submit manual KIS financial load", exception);
        }
    }

    private void refresh() {
        try {
            syncService.refreshRankedTargets(SyncTrigger.MANUAL);
        } catch (Exception exception) {
            log.error("Failed to run manual KIS financial load", exception);
        }
    }
}
