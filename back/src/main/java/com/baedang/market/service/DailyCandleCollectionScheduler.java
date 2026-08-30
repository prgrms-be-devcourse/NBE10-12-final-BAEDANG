package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 장 마감 후 상위 종목 일봉 자동 수집 스케줄러.
 */
@Component
@ConditionalOnProperty(name = "toss.enabled", havingValue = "true")
public class DailyCandleCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleCollectionScheduler.class);

    private final DailyCandleCollectionService dailyCandleCollectionService;
    private final Executor dailyCandleTaskExecutor;

    public DailyCandleCollectionScheduler(
            DailyCandleCollectionService dailyCandleCollectionService,
            @Qualifier("dailyCandleTaskExecutor") Executor dailyCandleTaskExecutor
    ) {
        this.dailyCandleCollectionService = dailyCandleCollectionService;
        this.dailyCandleTaskExecutor = dailyCandleTaskExecutor;
    }

    /** 국내 장 마감(15:30 KST) 후 15:40 수집 */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectKr() {
        log.info("[daily-candle] KR 장 마감 수집 트리거");
        submit(MarketCountry.KR);
    }

    /** 미국 현지 정규장 마감(16:00 ET) 10분 후 실행하며 DST 변환은 스케줄러 시간대에 맡깁니다. */
    @Scheduled(cron = "0 10 16 * * MON-FRI", zone = "America/New_York")
    public void collectUs() {
        log.info("[daily-candle] US 장 마감 수집 트리거");
        submit(MarketCountry.US);
    }

    private void submit(MarketCountry marketCountry) {
        CompletableFuture.runAsync(
                () -> dailyCandleCollectionService.collect(marketCountry),
                dailyCandleTaskExecutor
        ).exceptionally(exception -> {
            log.error("[daily-candle] 비동기 수집 실패: market={}", marketCountry, exception);
            return null;
        });
    }
}
