package com.baedang.stock.runner;

import com.baedang.stock.service.StockMasterDetailLoadService;
import com.baedang.stock.service.StockMasterLoadService;
import com.baedang.stock.service.StockRankingLoadService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StockMasterLoadRunner implements ApplicationRunner {
    private final StockMasterLoadService stockMasterLoadService;
    private final StockMasterDetailLoadService stockMasterDetailLoadService;
    private final StockRankingLoadService stockRankingLoadService;
    public StockMasterLoadRunner(
            StockMasterLoadService stockMasterLoadService,
            StockMasterDetailLoadService stockMasterDetailLoadService,
            StockRankingLoadService stockRankingLoadService
    ) {
        this.stockMasterLoadService = stockMasterLoadService;
        this.stockMasterDetailLoadService = stockMasterDetailLoadService;
        this.stockRankingLoadService = stockRankingLoadService;
    }

    private static final boolean IS_MASTER_ACTIVE = false;
    private static final boolean IS_MASTER_DETAIL_ACTIVE = false;
    private static final boolean IS_RANKING_ACTIVE = false;

    @Override
    public void run(ApplicationArguments args) {
        if (IS_MASTER_ACTIVE) stockMasterLoadService.loadAll();
        if (IS_MASTER_DETAIL_ACTIVE) stockMasterDetailLoadService.loadAll();
        if (IS_RANKING_ACTIVE) stockRankingLoadService.loadAll();
    }
}
