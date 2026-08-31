package com.baedang.market.scheduler;

import com.baedang.market.service.PrevCloseUpdateService;
import com.baedang.stock.entity.MarketCountry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 다음 정규장 시작 직전에 시장별 전일 종가를 갱신합니다. */
@Component
@ConditionalOnProperty(prefix = "toss", name = "enabled", havingValue = "true")
public class PrevCloseUpdateScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrevCloseUpdateScheduler.class);

    private final PrevCloseUpdateService prevCloseUpdateService;

    public PrevCloseUpdateScheduler(PrevCloseUpdateService prevCloseUpdateService) {
        this.prevCloseUpdateService = prevCloseUpdateService;
    }

    /** 국내 정규장 시작 10분 전인 08:50 KST에 갱신합니다. */
    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    public void updateKr() {
        update(MarketCountry.KR);
    }

    /** 미국 정규장 시작 30분 전인 09:00 ET에 갱신하며 DST는 시간대 설정에 맡깁니다. */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "America/New_York")
    public void updateUs() {
        update(MarketCountry.US);
    }

    private void update(MarketCountry marketCountry) {
        try {
            prevCloseUpdateService.update(marketCountry);
        } catch (Exception exception) {
            log.error("[prev-close] 갱신 실패: market={}", marketCountry, exception);
        }
    }
}
