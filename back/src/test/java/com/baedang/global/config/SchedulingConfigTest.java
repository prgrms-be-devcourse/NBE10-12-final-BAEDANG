package com.baedang.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @Test
    void 일봉_작업_Executor는_단일_스레드로_구성된다() {
        ThreadPoolTaskExecutor executor = new SchedulingConfig().dailyCandleTaskExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("daily-candle-");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void 호가_작업이_막혀도_공용_스케줄러는_실행된다() throws Exception {
        SchedulingConfig config = new SchedulingConfig();
        ThreadPoolTaskScheduler common = config.taskScheduler();
        ThreadPoolTaskScheduler orderBook = config.orderBookTaskScheduler();
        common.initialize();
        orderBook.initialize();
        CountDownLatch orderBookBlocked = new CountDownLatch(1);
        CountDownLatch releaseOrderBook = new CountDownLatch(1);
        CountDownLatch commonCompleted = new CountDownLatch(1);
        AtomicReference<String> orderBookThread = new AtomicReference<>();
        try {
            orderBook.execute(() -> {
                orderBookThread.set(Thread.currentThread().getName());
                orderBookBlocked.countDown();
                try {
                    releaseOrderBook.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(orderBookBlocked.await(3, TimeUnit.SECONDS)).isTrue();

            common.execute(commonCompleted::countDown);

            assertThat(commonCompleted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(orderBookThread.get()).startsWith("orderbook-");
        } finally {
            releaseOrderBook.countDown();
            common.shutdown();
            orderBook.shutdown();
        }
    }
}
