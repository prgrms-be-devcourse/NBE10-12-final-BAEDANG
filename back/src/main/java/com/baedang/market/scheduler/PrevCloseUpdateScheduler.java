package com.baedang.market.scheduler;

import com.baedang.market.service.PrevCloseUpdateService;
import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 서버 시작 후 누락된 기준가를 복구하고 특정 cron 시각과 무관하게 주기적으로 재시도한다. */
@Component
@ConditionalOnProperty(prefix = "toss", name = "enabled", havingValue = "true")
public class PrevCloseUpdateScheduler {
    private static final Logger log = LoggerFactory.getLogger(PrevCloseUpdateScheduler.class);
    private final PrevCloseUpdateService service;
    public PrevCloseUpdateScheduler(PrevCloseUpdateService service) { this.service = service; }

    @Scheduled(initialDelayString = "${trading.reference-recovery.initial-delay:5s}",
            fixedDelayString = "${trading.reference-recovery.interval:1m}", scheduler = "referenceRecoveryScheduler")
    public void recover() {
        for (MarketCountry country : MarketCountry.values()) {
            try { service.update(country); }
            catch (RuntimeException exception) {
                log.warn("[prev-close] market recovery deferred: market={} type={}", country, exception.getClass().getSimpleName());
            }
        }
    }
}
