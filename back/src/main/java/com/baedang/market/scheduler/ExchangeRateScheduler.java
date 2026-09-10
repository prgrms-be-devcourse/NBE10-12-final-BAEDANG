package com.baedang.market.scheduler;

import com.baedang.market.service.ExchangeRateLoadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "toss", name = "enabled", havingValue = "true")
public class ExchangeRateScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateScheduler.class);

    private final ExchangeRateLoadService loadService;
    public ExchangeRateScheduler(ExchangeRateLoadService exchangeRateLoadService) {
        this.loadService = exchangeRateLoadService;
    }

    /**
     * 매분 환율과 원본 유효기간을 적재합니다. 거래 요청에서는 외부 환율을 조회하지 않습니다.
     */
    @Scheduled(cron = "0 * * * * *")
    public void collect() {
        try {
            loadService.syncExchangeRate();
        } catch (Exception e) {
            log.error("환율 정기 수집 중 오류가 발생했습니다.", e);
        }
    }

}
