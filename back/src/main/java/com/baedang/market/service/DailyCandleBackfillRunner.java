package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 앱 기동 시 일봉이 없는 상위 종목을 대상으로 과거 250봉 초기 백필을 수행하는 러너.
 */
@Component
@ConditionalOnProperty(name = "toss.enabled", havingValue = "true")
public class DailyCandleBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleBackfillRunner.class);

    private final DailyCandleCollectionService dailyCandleCollectionService;

    public DailyCandleBackfillRunner(DailyCandleCollectionService dailyCandleCollectionService) {
        this.dailyCandleCollectionService = dailyCandleCollectionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[daily-candle-backfill] 초기 적재 시작 (KR → US 순)");
        dailyCandleCollectionService.backfill(MarketCountry.KR);
        dailyCandleCollectionService.backfill(MarketCountry.US);
        log.info("[daily-candle-backfill] 초기 적재 완료");
    }
}
