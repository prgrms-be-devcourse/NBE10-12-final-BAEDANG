package com.baedang.market.service;

import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.baedang.global.error.BusinessException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExchangeRateLoadServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-26T06:00:00Z");

    private static final OffsetDateTime COLLECTED_AT =
            NOW.atOffset(ZoneOffset.UTC);

    @Mock
    private MarketCalendarPort marketCalendarPort;

    @Mock
    private ExchangeRatePersistenceService persistenceService;

    private ExchangeRateLoadService loadService;
    private ExecutorService worker;

    @BeforeEach
    void setUp() {
        worker = Executors.newSingleThreadExecutor();
        loadService = new ExchangeRateLoadService(
                marketCalendarPort,
                persistenceService,
                Clock.fixed(NOW, ZoneOffset.UTC), worker, Duration.ofSeconds(5)
        );
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void 최초_호출도_대기시간을_넘기면_실패하지만_공유작업은_유지한다() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        java.util.concurrent.Executor executor = mock(java.util.concurrent.Executor.class);
        doAnswer(invocation -> { queued.set(invocation.getArgument(0)); return null; })
                .when(executor).execute(any(Runnable.class));
        loadService = new ExchangeRateLoadService(marketCalendarPort, persistenceService,
                Clock.fixed(NOW, ZoneOffset.UTC), executor, Duration.ofMillis(20));
        org.assertj.core.api.Assertions.assertThatThrownBy(loadService::syncExchangeRate)
                .isInstanceOf(BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(loadService::syncExchangeRate)
                .isInstanceOf(BusinessException.class);
        verify(executor).execute(any(Runnable.class));
        verifyNoInteractions(marketCalendarPort);
        queued.get().run();
        assertThat(loadService.syncExchangeRate()).isFalse();
        verify(marketCalendarPort).fetchExchangeRate();
    }

    @Test
    @DisplayName("외부 환율을 조회하고 고정된 수집 시각으로 적재한다")
    void t1() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "USD",
                "KRW",
                new BigDecimal("1400"),
                new BigDecimal("1398"),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC)
        );

        when(marketCalendarPort.fetchExchangeRate()).thenReturn(quote);

        when(persistenceService.saveIfValid(quote, COLLECTED_AT)).thenReturn(true);

        boolean inserted = loadService.syncExchangeRate();

        assertThat(inserted).isTrue();

        verify(persistenceService).saveIfValid(quote, COLLECTED_AT);
    }

    @Test
    void 동시_정기수집과_시장가_복구는_하나의_수집을_공유한다() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(marketCalendarPort.fetchExchangeRate()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return null;
        });
        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<Boolean> first = executor.submit(loadService::syncExchangeRate);
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<Boolean> second = executor.submit(loadService::syncExchangeRate);
            release.countDown();
            assertThat(first.get(3, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            assertThat(second.get(3, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            verify(marketCalendarPort).fetchExchangeRate();
        } finally {
            release.countDown();
        }
    }

    @Test
    void 실패_직후_연속_호출은_외부_API를_반복하지_않는다() {
        when(marketCalendarPort.fetchExchangeRate()).thenThrow(new IllegalStateException());
        org.assertj.core.api.Assertions.assertThatThrownBy(loadService::syncExchangeRate).isInstanceOf(BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(loadService::syncExchangeRate).isInstanceOf(BusinessException.class);
        verify(marketCalendarPort).fetchExchangeRate();
    }

    @Test
    @DisplayName("외부 환율 응답이 null이면 적재하지 않는다")
    void t2() {
        when(marketCalendarPort.fetchExchangeRate()).thenReturn(null);

        boolean inserted = loadService.syncExchangeRate();

        assertThat(inserted).isFalse();
        verifyNoInteractions(persistenceService);
    }
}
