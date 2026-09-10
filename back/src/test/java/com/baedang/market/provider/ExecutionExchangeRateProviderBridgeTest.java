package com.baedang.market.provider;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.port.MarketCalendarPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionExchangeRateProviderBridgeTest {
    private static final Instant AT = Instant.parse("2026-09-04T01:00:00Z");
    private final MarketCalendarPort port = mock(MarketCalendarPort.class);
    private final Clock clock = mock(Clock.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(AT);
    private ExecutionExchangeRateProviderBridge provider;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenAnswer(invocation -> now.get());
        provider = new ExecutionExchangeRateProviderBridge(port, clock);
        when(port.fetchExchangeRate()).thenAnswer(invocation -> quote(now.get(), 3600));
    }

    @Test
    void 단일환율과_스냅샷_조회는_원본정밀도를_보존하고_외부호출을_공유한다() {
        var snapshot = provider.currentUsdKrwSnapshot();
        assertThat(snapshot.rate()).isEqualTo(new BigDecimal("1383.601234"));
        assertThat(provider.currentUsdKrwRate()).isEqualTo(snapshot.rate());
        assertThat(provider.currentUsdKrwSnapshot()).isEqualTo(snapshot);
        verify(port, times(1)).fetchExchangeRate();
    }

    @Test
    void TTL_직전은_재사용하고_정확히_60초부터_재조회한다() {
        var first = provider.currentUsdKrwSnapshot();
        now.set(AT.plusSeconds(59));
        assertThat(provider.currentUsdKrwSnapshot()).isEqualTo(first);
        verify(port, times(1)).fetchExchangeRate();
        now.set(AT.plusSeconds(60));
        assertThat(provider.currentUsdKrwSnapshot().fetchedAt()).isEqualTo(AT.plusSeconds(60).atOffset(ZoneOffset.UTC));
        verify(port, times(2)).fetchExchangeRate();
    }

    @Test
    void 원본_유효기간이_TTL보다_짧으면_그_경계에서_재조회한다() {
        when(port.fetchExchangeRate()).thenReturn(quote(AT, 10), quote(AT.plusSeconds(10), 3600));
        var first = provider.currentUsdKrwSnapshot();
        now.set(AT.plusSeconds(10));
        var refreshed = provider.currentUsdKrwSnapshot();
        assertThat(refreshed.fetchedAt()).isEqualTo(AT.plusSeconds(10).atOffset(ZoneOffset.UTC));
        assertThat(refreshed.isValidAt(now.get().atOffset(ZoneOffset.UTC))).isTrue();
        verify(port, times(2)).fetchExchangeRate();
        assertThat(first.isValidAt(now.get().atOffset(ZoneOffset.UTC))).isFalse();
    }

    @Test
    void fetchedAt은_외부호출_시작이_아닌_수신완료시각이다() {
        when(port.fetchExchangeRate()).thenAnswer(invocation -> {
            now.set(AT.plusSeconds(5));
            return quote(AT, 3600);
        });
        assertThat(provider.currentUsdKrwSnapshot().fetchedAt()).isEqualTo(AT.plusSeconds(5).atOffset(ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "currency", "missingRate", "zero", "negative", "scale", "missingFrom", "missingUntil", "future", "expired", "inverted"})
    void 잘못된_원본은_캐시하지_않고_기존_환율오류를_사용한다(String kind) {
        var from = AT.atOffset(ZoneOffset.UTC);
        var until = from.plusHours(1);
        var rate = new BigDecimal("1300");
        switch (kind) {
            case "missingRate" -> rate = null;
            case "zero" -> rate = BigDecimal.ZERO;
            case "negative" -> rate = BigDecimal.ONE.negate();
            case "scale" -> rate = new BigDecimal("1300.1234567");
            case "missingFrom" -> from = null;
            case "missingUntil" -> until = null;
            case "future" -> from = from.plusSeconds(1);
            case "expired" -> until = from;
            case "inverted" -> until = from.minusSeconds(1);
        }
        var bad = kind.equals("null") ? null : new ExchangeRateQuote(kind.equals("currency") ? "KRW" : "USD",
                "KRW", rate, BigDecimal.ONE, from, until);
        when(port.fetchExchangeRate()).thenReturn(bad, quote(AT, 3600));
        assertThatThrownBy(provider::currentUsdKrwSnapshot).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND));
        assertThat(provider.currentUsdKrwSnapshot()).isNotNull();
        verify(port, times(2)).fetchExchangeRate();
    }

    @Test
    void 캐시만료후_장애가_나도_오래된_값으로_폴백하지_않는다() {
        provider.currentUsdKrwSnapshot();
        now.set(AT.plusSeconds(60));
        when(port.fetchExchangeRate()).thenThrow(new BusinessException(ErrorCode.TOSS_API_ERROR));
        assertThatThrownBy(provider::currentUsdKrwRate).isInstanceOf(BusinessException.class);
    }

    @Test
    void 동시조회는_한번만_외부호출하고_같은_스냅샷을_공유한다() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<ExecutionExchangeRateSnapshot>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> {
                start.await();
                return provider.currentUsdKrwSnapshot();
            }));
            start.countDown();
            var first = futures.getFirst().get(5, TimeUnit.SECONDS);
            for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(first);
        }
        verify(port, times(1)).fetchExchangeRate();
    }

    private ExchangeRateQuote quote(Instant from, long seconds) {
        OffsetDateTime validFrom = from.atOffset(ZoneOffset.UTC);
        return new ExchangeRateQuote("USD", "KRW", new BigDecimal("1383.601234"), new BigDecimal("1300"),
                validFrom, validFrom.plusSeconds(seconds));
    }
}
