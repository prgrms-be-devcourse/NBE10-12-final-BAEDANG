package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 앱 기동 완료 후 상위 종목의 최근 250봉을 멱등 UPSERT하여 누락을 복구하는 비동기 러너.
 */
@Component
@ConditionalOnProperty(name = "toss.enabled", havingValue = "true")
public class DailyCandleBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleBackfillRunner.class);

    private final DailyCandleCollectionService dailyCandleCollectionService;
    private final Executor dailyCandleTaskExecutor;

    public DailyCandleBackfillRunner(
            DailyCandleCollectionService dailyCandleCollectionService,
            @Qualifier("dailyCandleTaskExecutor") Executor dailyCandleTaskExecutor
    ) {
        this.dailyCandleCollectionService = dailyCandleCollectionService;
        this.dailyCandleTaskExecutor = dailyCandleTaskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[daily-candle-backfill] 비동기 초기 적재 시작 (KR → US 순)");
                dailyCandleCollectionService.backfill(MarketCountry.KR);
                dailyCandleCollectionService.backfill(MarketCountry.US);
                log.info("[daily-candle-backfill] 비동기 초기 적재 완료");
            } catch (Exception exception) {
                log.error("[daily-candle-backfill] 비동기 초기 적재 중 예외 발생", exception);
            }
        }, dailyCandleTaskExecutor);
    }
}
