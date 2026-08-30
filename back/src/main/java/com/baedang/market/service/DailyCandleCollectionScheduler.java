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

    /** DST 여부와 무관하게 미국 정규장 마감 이후인 06:10 KST에 실행하고 캘린더로 휴장 여부를 확인합니다. */
    @Scheduled(cron = "0 10 6 * * TUE-SAT", zone = "Asia/Seoul")
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
