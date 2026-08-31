package com.baedang.stock.scheduler;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.service.StockRankingLoadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "toss", name = "enabled", havingValue = "true")
public class StockRankingCollectionScheduler {
    private final StockRankingLoadService stockRankingLoadService;

    public StockRankingCollectionScheduler(StockRankingLoadService stockRankingLoadService) {
        this.stockRankingLoadService = stockRankingLoadService;
    }

    private static final Logger log = LoggerFactory.getLogger(StockRankingCollectionScheduler.class);

    /** 국내 정규장 개장(09:00 KST) 1시간 전에 이번 주 유니버스를 갱신한다. */
    @Scheduled(cron = "0 0 8 * * MON", zone = "Asia/Seoul")
    public void loadKr() {
        load(MarketCountry.KR);
    }

    /**
     * 미국 정규장 개장 1시간 30분 전(서머타임 기준 22:30 KST)에 갱신한다. 겨울에는 개장이
     * 23:30 KST 로 밀리지만, 갱신은 개장 전이기만 하면 되므로 21:00 KST 로 고정한다.
     */
    @Scheduled(cron = "0 0 21 * * MON", zone = "Asia/Seoul")
    public void loadUs() {
        load(MarketCountry.US);
    }

    private void load(MarketCountry marketCountry) {
        log.info("[stock-ranking] 주간 유니버스 갱신 트리거: market={}", marketCountry);

        try {
            stockRankingLoadService.load(marketCountry);
            log.info("[stock-ranking] 주간 유니버스 갱신 완료: market={}", marketCountry);
        } catch (Exception exception) {
            log.error("[stock-ranking] 주간 유니버스 갱신 실패: market={}", marketCountry, exception);
        }
    }
}
