package com.baedang.stock.runner;

import com.baedang.stock.service.StockMasterDetailLoadService;
import com.baedang.stock.service.StockMasterLoadService;
import com.baedang.stock.service.StockRankingLoadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기동 시 1회 수동 적재 트리거. 세 플래그 모두 기본 false 이며 {@code .env} 로 켭니다.
 *
 * <p>실데이터 적재에는 {@code toss.enabled=true} 도 함께 필요합니다.
 */
@Component
public class StockMasterLoadRunner implements ApplicationRunner {
    private final StockMasterLoadService stockMasterLoadService;
    private final StockMasterDetailLoadService stockMasterDetailLoadService;
    private final StockRankingLoadService stockRankingLoadService;

    private final boolean masterActive;
    private final boolean masterDetailActive;
    private final boolean rankingActive;

    public StockMasterLoadRunner(
            StockMasterLoadService stockMasterLoadService,
            StockMasterDetailLoadService stockMasterDetailLoadService,
            StockRankingLoadService stockRankingLoadService,
            @Value("${toss.load-stock-master:false}") boolean masterActive,
            @Value("${toss.load-stock-master-detail:false}") boolean masterDetailActive,
            @Value("${toss.load-stock-ranking:false}") boolean rankingActive
    ) {
        this.stockMasterLoadService = stockMasterLoadService;
        this.stockMasterDetailLoadService = stockMasterDetailLoadService;
        this.stockRankingLoadService = stockRankingLoadService;
        this.masterActive = masterActive;
        this.masterDetailActive = masterDetailActive;
        this.rankingActive = rankingActive;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (masterActive) stockMasterLoadService.loadAll();
        if (masterDetailActive) stockMasterDetailLoadService.loadAll();
        if (rankingActive) stockRankingLoadService.loadAll();
    }
}
