package com.baedang.market.provider;

import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingMarketCalendarPortTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 2);

    private final MarketCalendarPort delegate = mock(MarketCalendarPort.class);
    private final CachingMarketCalendarPort cache = new CachingMarketCalendarPort(delegate);

    @Test
    void 같은_시장과_날짜는_원본_Port를_한_번만_호출한다() {
        MarketCalendarDay calendarDay = openDay(MarketCountry.KR, DATE);
        when(delegate.fetchKrMarketCalendar(DATE)).thenReturn(calendarDay);

        assertThat(cache.fetchKrMarketCalendar(DATE)).isSameAs(calendarDay);
        assertThat(cache.fetchKrMarketCalendar(DATE)).isSameAs(calendarDay);

        verify(delegate, times(1)).fetchKrMarketCalendar(DATE);
        assertThat(cache.entryCount()).isOne();
    }

    @Test
    void 시장과_날짜가_다르면_각각_캐싱한다() {
        LocalDate nextDate = DATE.plusDays(1);
        when(delegate.fetchKrMarketCalendar(DATE)).thenReturn(openDay(MarketCountry.KR, DATE));
        when(delegate.fetchUsMarketCalendar(DATE)).thenReturn(openDay(MarketCountry.US, DATE));
        when(delegate.fetchKrMarketCalendar(nextDate))
                .thenReturn(openDay(MarketCountry.KR, nextDate));

        cache.fetchKrMarketCalendar(DATE);
        cache.fetchUsMarketCalendar(DATE);
        cache.fetchKrMarketCalendar(nextDate);

        assertThat(cache.entryCount()).isEqualTo(3);
    }

    @Test
    void 원본_Port_실패는_캐싱하지_않아_다음_호출에서_재시도한다() {
        MarketCalendarDay recovered = openDay(MarketCountry.KR, DATE);
        when(delegate.fetchKrMarketCalendar(DATE))
                .thenThrow(new RuntimeException("일시 장애"))
                .thenReturn(recovered);

        assertThatThrownBy(() -> cache.fetchKrMarketCalendar(DATE))
                .isInstanceOf(RuntimeException.class);
        assertThat(cache.fetchKrMarketCalendar(DATE)).isSameAs(recovered);

        verify(delegate, times(2)).fetchKrMarketCalendar(DATE);
        assertThat(cache.entryCount()).isOne();
    }

    @Test
    void 동시_요청도_같은_키의_원본_Port를_한_번만_호출한다() throws Exception {
        MarketCalendarDay calendarDay = openDay(MarketCountry.KR, DATE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(delegate.fetchKrMarketCalendar(DATE)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return calendarDay;
        });

        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MarketCalendarDay>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < threadCount; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cache.fetchKrMarketCalendar(DATE);
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            for (Future<MarketCalendarDay> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(calendarDay);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        verify(delegate, times(1)).fetchKrMarketCalendar(DATE);
    }

    @Test
    void 환율은_캘린더_캐시_대상이_아니다() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "USD",
                "KRW",
                new BigDecimal("1390.00"),
                new BigDecimal("1380.00"),
                OffsetDateTime.of(2026, 9, 2, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 9, 2, 1, 0, 0, 0, ZoneOffset.UTC)
        );
        when(delegate.fetchExchangeRate()).thenReturn(quote);

        cache.fetchExchangeRate();
        cache.fetchExchangeRate();

        verify(delegate, times(2)).fetchExchangeRate();
        assertThat(cache.entryCount()).isZero();
    }

    private MarketCalendarDay openDay(MarketCountry marketCountry, LocalDate tradeDate) {
        OffsetDateTime openAt = tradeDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        return new MarketCalendarDay(
                marketCountry,
                tradeDate,
                true,
                openAt,
                openAt.plusHours(6),
                null
        );
    }
}
