package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 앱 기동 완료 후 일봉이 없는 상위 종목을 대상으로 과거 250봉 초기 백필을 비동기로 수행하는 러너.
 */
@Component
@ConditionalOnProperty(name = "toss.enabled", havingValue = "true")
public class DailyCandleBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleBackfillRunner.class);

    private final DailyCandleCollectionService dailyCandleCollectionService;

    public DailyCandleBackfillRunner(DailyCandleCollectionService dailyCandleCollectionService) {
        this.dailyCandleCollectionService = dailyCandleCollectionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[daily-candle-backfill] 비동기 초기 적재 시작 (KR → US 순)");
                dailyCandleCollectionService.backfill(MarketCountry.KR);
                dailyCandleCollectionService.backfill(MarketCountry.US);
                log.info("[daily-candle-backfill] 비동기 초기 적재 완료");
            } catch (Throwable t) {
                log.error("[daily-candle-backfill] 비동기 초기 적재 중 예외 발생", t);
            }
        });
    }
}

