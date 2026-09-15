package com.baedang.market.service;

import com.baedang.global.metrics.TradingMetrics;
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
@ConditionalOnProperty(prefix = "toss", name = "enabled", havingValue = "true")
public class DailyCandleCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyCandleCollectionScheduler.class);

    private final DailyCandleCollectionService dailyCandleCollectionService;
    private final Executor dailyCandleTaskExecutor;
    private final TradingMetrics metrics;

    public DailyCandleCollectionScheduler(
            DailyCandleCollectionService dailyCandleCollectionService,
            @Qualifier("dailyCandleTaskExecutor") Executor dailyCandleTaskExecutor,
            TradingMetrics metrics
    ) {
        this.dailyCandleCollectionService = dailyCandleCollectionService;
        this.dailyCandleTaskExecutor = dailyCandleTaskExecutor;
        this.metrics = metrics;
    }

    /** 국내 장 마감 후 15:40부터 17:10까지 30분 간격으로 재시도합니다. */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(cron = "0 10,40 16 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(cron = "0 10 17 * * MON-FRI", zone = "Asia/Seoul")
    public void collectKr() {
        log.info("[daily-candle] KR 장 마감 수집 트리거");
        submit(MarketCountry.KR);
    }

    /** 미국 현지 정규장 마감 후 16:10부터 17:10까지 30분 간격으로 재시도합니다. */
    @Scheduled(cron = "0 10,40 16 * * MON-FRI", zone = "America/New_York")
    @Scheduled(cron = "0 10 17 * * MON-FRI", zone = "America/New_York")
    public void collectUs() {
        log.info("[daily-candle] US 장 마감 수집 트리거");
        submit(MarketCountry.US);
    }

    private void submit(MarketCountry marketCountry) {
        CompletableFuture.runAsync(
                () -> {
                    // collect() 가 "실제로 당일 데이터가 존재함"을 true 로 돌려줄 때만 성공 시각을 기록한다.
                    // collect() 는 종목별 예외를 삼키고 전량 미적재에도 정상 반환하므로, 반환값을 봐야
                    // 거짓 성공(데이터 0건인데 성공 기록)을 막는다(PR #213 리뷰 반영).
                    // KR/US 는 스케줄·실패 지점이 독립적이라 market 태그로 시계열을 분리한다.
                    if (dailyCandleCollectionService.collect(marketCountry)) {
                        metrics.batchSucceeded("daily-candle", marketCountry.name());
                    }
                },
                dailyCandleTaskExecutor
        ).exceptionally(exception -> {
            log.error("[daily-candle] 비동기 수집 실패: market={}", marketCountry, exception);
            return null;
        });
    }
}
