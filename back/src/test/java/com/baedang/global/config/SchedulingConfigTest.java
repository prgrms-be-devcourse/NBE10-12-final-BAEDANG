package com.baedang.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SchedulingConfigTest {

    @Test
    void 리더보드_종료는_미실행_예약을_취소하고_즉시_끝난다() throws Exception {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfig().leaderboardTaskScheduler();
        scheduler.initialize();
        AtomicBoolean executed = new AtomicBoolean();
        var reservation = scheduler.schedule(() -> executed.set(true), Instant.now().plus(Duration.ofDays(1)));

        try (var closer = Executors.newSingleThreadExecutor()) {
            try {
                closer.submit(scheduler::shutdown).get(3, TimeUnit.SECONDS);

                assertThat(reservation.isCancelled()).isTrue();
                assertThat(executed).isFalse();
                assertThat(scheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
            } finally {
                scheduler.getScheduledThreadPoolExecutor().shutdownNow();
            }
        }
    }

    @Test
    void 리더보드_취소된_예약은_대기열에서_즉시_제거된다() {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfig().leaderboardTaskScheduler();
        scheduler.initialize();
        try {
            var reservation = scheduler.schedule(() -> {}, Instant.now().plus(Duration.ofDays(1)));
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);

            assertThat(reservation.cancel(false)).isTrue();

            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
        } finally {
            scheduler.getScheduledThreadPoolExecutor().shutdownNow();
        }
    }

    @Test
    void 리더보드_종료는_실행중인_작업을_중단하지_않고_완료를_기다린다() throws Exception {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfig().leaderboardTaskScheduler();
        scheduler.initialize();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var closer = Executors.newSingleThreadExecutor()) {
            try {
                var running = scheduler.submit(() -> {
                    started.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return "completed";
                });
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();

                var shutdown = closer.submit(scheduler::shutdown);
                await().atMost(Duration.ofSeconds(3))
                        .until(() -> scheduler.getScheduledThreadPoolExecutor().isShutdown());
                assertThat(shutdown).isNotDone();
                assertThat(running).isNotDone();

                release.countDown();

                assertThat(running.get(3, TimeUnit.SECONDS)).isEqualTo("completed");
                shutdown.get(3, TimeUnit.SECONDS);
                assertThat(scheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
            } finally {
                release.countDown();
                scheduler.getScheduledThreadPoolExecutor().shutdownNow();
            }
        }
    }

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
    void 재무정보_작업_Executor는_대기열이_제한된_단일_스레드로_구성된다() {
        ThreadPoolTaskExecutor executor = new SchedulingConfig().stockFinancialTaskExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("stock-financial-");
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(1);
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
