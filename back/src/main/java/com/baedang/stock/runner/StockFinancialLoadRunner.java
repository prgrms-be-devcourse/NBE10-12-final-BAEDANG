package com.baedang.stock.runner;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.baedang.global.clients.kis.KisProperties;
import com.baedang.stock.service.StockFinancialSyncService;
import com.baedang.stock.service.StockFinancialSyncService.SyncTrigger;

@Component
public class StockFinancialLoadRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockFinancialLoadRunner.class);

    private final StockFinancialSyncService syncService;
    private final KisProperties properties;

    public StockFinancialLoadRunner(StockFinancialSyncService syncService, KisProperties properties) {
        this.syncService = Objects.requireNonNull(syncService, "syncService");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.loadFinancials()) {
            return;
        }
        try {
            syncService.refreshRankedTargets(SyncTrigger.MANUAL);
        } catch (Exception exception) {
            log.error("Failed to run manual KIS financial load", exception);
        }
    }
}
