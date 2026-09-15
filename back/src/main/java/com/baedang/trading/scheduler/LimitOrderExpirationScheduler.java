package com.baedang.trading.scheduler;

import com.baedang.trading.service.LimitOrderExpirationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** 주기 실행과 시작 시 복구 모두 만료 전용 스케줄러를 사용합니다. */
@Component
public class LimitOrderExpirationScheduler {
    private final LimitOrderExpirationService expirationService;
    private final ThreadPoolTaskScheduler scheduler;

    public LimitOrderExpirationScheduler(LimitOrderExpirationService expirationService,
            @Qualifier("limitOrderTaskScheduler") ThreadPoolTaskScheduler scheduler) {
        this.expirationService = expirationService;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelay = 30_000, scheduler = "limitOrderTaskScheduler")
    public void scan() {
        expirationService.expireDue();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        scheduler.execute(expirationService::expireDue);
    }
}
