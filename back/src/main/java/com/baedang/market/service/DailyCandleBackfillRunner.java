package com.baedang.market.service;

import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 앱 기동 시 {@code daily_candle} 초기 적재(Backfill)를 수행합니다.
 *
 * <p>일봉이 한 개도 없는 거래대금 상위 종목에 대해 최대 250개(약 1년치)의 과거 봉을 수집합니다.
 * 이미 데이터가 있는 종목은 건너뜨므로, 재배포 시 중복 API 호출이 발생하지 않습니다.
 *
 * <p>토스 키가 없는 로컬 환경에서 기동 오류가 발생하지 않도록,
 * {@code toss.enabled=true} 일 때만 빈으로 등록됩니다.
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
