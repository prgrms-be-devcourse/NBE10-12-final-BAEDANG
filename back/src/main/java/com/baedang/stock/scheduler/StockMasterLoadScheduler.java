package com.baedang.stock.scheduler;


import com.baedang.stock.service.StockMasterDetailLoadService;
import com.baedang.stock.service.StockMasterLoadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "toss",
        name = "enabled",
        havingValue = "true"
)
public class StockMasterLoadScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockMasterLoadScheduler.class);

    private final StockMasterLoadService stockMasterLoadService;
    private final StockMasterDetailLoadService stockMasterDetailLoadService;

    public StockMasterLoadScheduler(
            StockMasterLoadService stockMasterLoadService,
            StockMasterDetailLoadService stockMasterDetailLoadService
    ) {
        this.stockMasterLoadService = stockMasterLoadService;
        this.stockMasterDetailLoadService = stockMasterDetailLoadService;
    }

    @Scheduled(
            cron = "0 0 7 * * MON",
            zone = "Asia/Seoul"
    )
    public void load() {
        try {
            stockMasterLoadService.loadAll();
            stockMasterDetailLoadService.loadAll();
        } catch (Exception e) {
            log.error("[stock-master] 주간 마스터 갱신 실패",e);
        }
    }
}
