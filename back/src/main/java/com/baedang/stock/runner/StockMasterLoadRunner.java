package com.baedang.stock.runner;

import com.baedang.stock.service.StockMasterDetailLoadService;
import com.baedang.stock.service.StockMasterLoadService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StockMasterLoadRunner implements ApplicationRunner {
    private final StockMasterLoadService stockMasterLoadService;
    private final StockMasterDetailLoadService stockMasterDetailLoadService;
    public StockMasterLoadRunner(
            StockMasterLoadService stockMasterLoadService,
            StockMasterDetailLoadService stockMasterDetailLoadService
    ) {
        this.stockMasterLoadService = stockMasterLoadService;
        this.stockMasterDetailLoadService = stockMasterDetailLoadService;
    }

    private static final boolean IS_MASTER_ACTIVE = false;
    private static final boolean IS_MASTER_DETAIL_ACTIVE = false;

    @Override
    public void run(ApplicationArguments args) {
        if (IS_MASTER_ACTIVE) stockMasterLoadService.loadAll();
        if (IS_MASTER_DETAIL_ACTIVE) stockMasterDetailLoadService.loadAll();
    }
}
