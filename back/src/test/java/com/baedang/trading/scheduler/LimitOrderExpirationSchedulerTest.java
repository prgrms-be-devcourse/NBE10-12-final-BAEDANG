package com.baedang.trading.scheduler;

import com.baedang.global.config.SchedulingConfig;
import com.baedang.trading.service.LimitOrderExpirationService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class LimitOrderExpirationSchedulerTest {
    @Test
    void 공용스케줄러가_막혀도_시작복구는_전용스레드에서_실행한다() throws Exception {
        var config = new SchedulingConfig();
        var common = config.taskScheduler();
        var dedicated = config.limitOrderTaskScheduler();
        common.initialize();
        dedicated.initialize();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var thread = new AtomicReference<String>();
        var service = mock(LimitOrderExpirationService.class);
        doAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            completed.countDown();
            return null;
        }).when(service).expireDue();
        try {
            common.execute(() -> {
                blocked.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(blocked.await(3, TimeUnit.SECONDS)).isTrue();
            new LimitOrderExpirationScheduler(service, dedicated).recoverOnStartup();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("limit-order-expiration-");
        } finally {
            release.countDown();
            common.shutdown();
            dedicated.shutdown();
        }
    }
}
