package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 장 마감 후 상위 종목 일봉 자동 수집 스케줄러.
 */
@Component
@ConditionalOnProperty(name = "toss.enabled", havingValue = "true")
public class DailyCandleCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleCollectionScheduler.class);

    private final DailyCandleCollectionService dailyCandleCollectionService;

    public DailyCandleCollectionScheduler(DailyCandleCollectionService dailyCandleCollectionService) {
        this.dailyCandleCollectionService = dailyCandleCollectionService;
    }

    /** 국내 장 마감(15:30 KST) 후 15:40 수집 */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectKr() {
        log.info("[daily-candle] KR 장 마감 수집 트리거");
        dailyCandleCollectionService.collect(MarketCountry.KR);
    }

    /** 미국 장 마감 후 06:10 KST 수집 (DST 여름 05:00, 겨울 06:00 커버) */
    @Scheduled(cron = "0 10 6 * * TUE-SAT", zone = "Asia/Seoul")
    public void collectUs() {
        log.info("[daily-candle] US 장 마감 수집 트리거");
        dailyCandleCollectionService.collect(MarketCountry.US);
    }
}
