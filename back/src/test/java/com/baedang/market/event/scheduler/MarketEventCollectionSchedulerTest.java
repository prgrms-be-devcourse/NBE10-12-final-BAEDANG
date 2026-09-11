package com.baedang.market.event.scheduler;

import com.baedang.global.config.SchedulingConfig;
import com.baedang.global.config.TimeConfig;
import com.baedang.market.event.service.MarketEventCollectionService;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 장외에는 KIND를 호출하지 않고, 시작 복구는 공용 스케줄러가 아니라 전용 단일 스레드에서 실행한다.
 * 한 주기의 실패가 다음 주기를 막으면 안 된다.
 */
class MarketEventCollectionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-13T04:35:00Z");

    private final MarketEventCollectionService collection = mock(MarketEventCollectionService.class);
    private final MarketSessionProvider sessions = mock(MarketSessionProvider.class);

    private ThreadPoolTaskScheduler taskScheduler;
    private MarketEventCollectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskScheduler = new SchedulingConfig().marketEventTaskScheduler();
        taskScheduler.initialize();
        scheduler = new MarketEventCollectionScheduler(
                collection, sessions, Clock.fixed(NOW, ZoneOffset.UTC), taskScheduler);
    }

    @Test
    void startup_dispatches_collection_on_the_dedicated_scheduler_when_kr_is_open() throws Exception {
        when(sessions.isOpen(MarketCountry.KR, NOW)).thenReturn(true);
        CountDownLatch executed = new CountDownLatch(1);
        doAnswer(invocation -> {
            executed.countDown();
            return null;
        }).when(collection).collect();

        scheduler.recoverOnStartup();

        assertThat(executed.await(3, TimeUnit.SECONDS)).isTrue();
        verify(collection).collect();
    }

    @Test
    void startup_uses_the_dedicated_single_thread_scheduler() throws Exception {
        when(sessions.isOpen(MarketCountry.KR, NOW)).thenReturn(true);
        AtomicReference<String> thread = new AtomicReference<>();
        CountDownLatch executed = new CountDownLatch(1);
        doAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            executed.countDown();
            return null;
        }).when(collection).collect();

        scheduler.recoverOnStartup();

        assertThat(executed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.get()).startsWith("market-event-");
    }

    @Test
    void closed_market_does_not_call_kind() {
        when(sessions.isOpen(MarketCountry.KR, NOW)).thenReturn(false);

        scheduler.poll();

        verifyNoInteractions(collection);
    }

    @Test
    void open_market_polls_collection() {
        when(sessions.isOpen(any(), any())).thenReturn(true);

        scheduler.poll();

        verify(collection).collect();
    }

    /** Toss 캘린더 장애가 스케줄러 스레드를 죽이면 다음 주기 수집이 통째로 멈춘다. */
    @Test
    void session_lookup_failure_is_isolated_and_does_not_call_kind() {
        doThrow(new IllegalStateException("market calendar unavailable"))
                .when(sessions).isOpen(MarketCountry.KR, NOW);

        assertThatCode(() -> scheduler.poll()).doesNotThrowAnyException();

        verifyNoInteractions(collection);
    }

    /** 한 주기의 실패가 다음 주기를 막으면 예외 하나로 수집이 영구히 멈춘다. */
    @Test
    void collection_failure_is_isolated_and_the_next_poll_still_runs() {
        when(sessions.isOpen(any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("KIND unavailable"))
                .doNothing()
                .when(collection).collect();

        assertThatCode(() -> scheduler.poll()).doesNotThrowAnyException();
        assertThatCode(() -> scheduler.poll()).doesNotThrowAnyException();

        verify(collection, times(2)).collect();
    }

    /** 기본 머지 상태에서는 스케줄러 빈 자체가 없어 호출도 스케줄 등록도 일어나지 않는다. */
    @Test
    void scheduler_bean_exists_only_when_the_flag_is_on() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(TimeConfig.class, SchedulingConfig.class,
                        MarketEventCollectionScheduler.class)
                .withBean(MarketEventCollectionService.class, () -> collection)
                .withBean(MarketSessionProvider.class, () -> sessions);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MarketEventCollectionScheduler.class);
            assertThat(context).doesNotHaveBean("marketEventTaskScheduler");
        });

        runner.withPropertyValues("krx.market-events.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MarketEventCollectionScheduler.class);
            assertThat(context).hasBean("marketEventTaskScheduler");
        });
    }
}
