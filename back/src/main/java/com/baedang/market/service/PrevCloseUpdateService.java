package com.baedang.market.service;

import com.baedang.market.model.PrevCloseUpdateResult;
import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** 다음 정규장 시작 전에 상위 종목의 등락률 기준가를 갱신합니다. */
@Service
public class PrevCloseUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PrevCloseUpdateService.class);
    private static final int FALLBACK_WARNING_PERCENT = 50;

    private final LatestCompletedTradingDayResolver latestCompletedTradingDayResolver;
    private final PrevCloseUpdateTransactionService transactionService;

    public PrevCloseUpdateService(
            LatestCompletedTradingDayResolver latestCompletedTradingDayResolver,
            PrevCloseUpdateTransactionService transactionService
    ) {
        this.latestCompletedTradingDayResolver = latestCompletedTradingDayResolver;
        this.transactionService = transactionService;
    }

    public PrevCloseUpdateResult update(MarketCountry marketCountry) {
        Objects.requireNonNull(marketCountry, "marketCountry must not be null");

        Optional<LocalDate> expectedTradeDate = latestCompletedTradingDayResolver.resolve(marketCountry);
        if (expectedTradeDate.isEmpty()) {
            log.warn(
                    "[prev-close] 직전 거래일 확인 불가, last_price 전체 폴백: market={}",
                    marketCountry
            );
        }

        PrevCloseUpdateResult result = transactionService.update(marketCountry, expectedTradeDate);
        if (isExcessiveFallback(result)) {
            log.warn(
                    "[prev-close] 폴백 비율 과다: market={} expectedTradeDate={} fallback={}/{}",
                    marketCountry,
                    expectedTradeDate.map(LocalDate::toString).orElse("CALENDAR_UNAVAILABLE"),
                    result.fallbackCount(),
                    result.targetCount()
            );
        }
        log.info(
                "[prev-close] 갱신 완료: market={} expectedTradeDate={} target={} updated={} fallback={} skipped={}",
                marketCountry,
                expectedTradeDate.map(LocalDate::toString).orElse("LAST_PRICE_FALLBACK"),
                result.targetCount(),
                result.updatedCount(),
                result.fallbackCount(),
                result.skippedCount()
        );
        return result;
    }

    private boolean isExcessiveFallback(PrevCloseUpdateResult result) {
        return result.targetCount() > 0
                && result.fallbackCount() * 100L
                > result.targetCount() * FALLBACK_WARNING_PERCENT;
    }
}
