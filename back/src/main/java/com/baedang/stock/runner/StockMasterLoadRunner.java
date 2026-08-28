package com.baedang.stock.runner;

import com.baedang.stock.service.StockMasterLoadService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StockMasterLoadRunner implements ApplicationRunner {
    private final StockMasterLoadService stockMasterLoadService;

    public StockMasterLoadRunner(StockMasterLoadService stockMasterLoadService) {
        this.stockMasterLoadService = stockMasterLoadService;
    }

    private static final boolean IS_ACTIVE = false;

    @Override
    public void run(ApplicationArguments args) {
        if (!IS_ACTIVE) return;

        stockMasterLoadService.loadAll();
    }
}