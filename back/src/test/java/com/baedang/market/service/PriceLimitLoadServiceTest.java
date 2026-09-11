package com.baedang.market.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.global.clients.FixedIntervalGate;
import java.util.concurrent.atomic.AtomicLong;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceLimits;
import com.baedang.market.repository.PriceLimitRepository;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.orderbook.support.MutableClock;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PriceLimitLoadServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 11);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-11T01:00:00Z"));
    private final MarketDataPort data = mock(MarketDataPort.class);
    private final MarketTradingDayPolicy days = mock(MarketTradingDayPolicy.class);
    private final QuoteSnapshotRepository quotes = mock(QuoteSnapshotRepository.class);
    private final PriceLimitRepository persistence = mock(PriceLimitRepository.class);
    private final PriceLimitLoadService service = new PriceLimitLoadService(data, days, quotes, persistence, clock, true);
    private final Stock stock = mock(Stock.class);
    private final QuoteSnapshot quote = mock(QuoteSnapshot.class);

    @BeforeEach
    void setup() {
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("005930");
        when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        when(quotes.findById(1L)).thenReturn(Optional.of(quote));
        when(days.calendar(eq(MarketCountry.KR), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);
            return new MarketCalendarDay(MarketCountry.KR, date, true,
                    date.atTime(9, 0).atOffset(ZoneOffset.ofHours(9)),
                    date.atTime(15, 30).atOffset(ZoneOffset.ofHours(9)), null);
        });
        when(data.fetchPriceLimits("005930")).thenReturn(limits("2026-09-11T09:00:00+09:00"));
        when(persistence.save(any(), any(), any())).thenReturn(true);
    }

    private PriceLimits limits(String timestamp) {
        return new PriceLimits(OffsetDateTime.parse(timestamp), new BigDecimal("93000"), new BigDecimal("50400"), "KRW");
    }

    @Test
    void 시작시_당일_미확보분을_복구한다() {
        service.ensure(stock);
        verify(persistence).save(eq(1L), eq(DATE), any());
    }

    @Test
    void 확보한_날짜는_외부호출을_생략한다() {
        when(quote.getPriceLimitDate()).thenReturn(DATE);
        service.ensure(stock);
        verifyNoInteractions(data, persistence);
    }

    @Test
    void 미국은_정상_미제공이며_호출하지_않는다() {
        when(stock.getMarketCountry()).thenReturn(MarketCountry.US);
        service.ensure(stock);
        assertThat(service.canDisplay(stock, quote)).isFalse();
        verifyNoInteractions(data, quotes, days, persistence);
    }

    @Test
    void 장전과_휴일은_신규수집을_하지_않는다() {
        clock.setCurrent(Instant.parse("2026-09-11T23:00:00Z"));
        service.ensure(stock);
        when(days.calendar(any(), any())).thenReturn(new MarketCalendarDay(MarketCountry.KR, DATE, false, null, null, null));
        clock.setCurrent(Instant.parse("2026-09-12T01:00:00Z"));
        service.ensure(stock);
        verifyNoInteractions(data, persistence);
    }

    @Test
    void 실패후_1분동안_상세와_주기요청을_억제한다() {
        when(data.fetchPriceLimits(any())).thenThrow(new IllegalStateException());
        service.ensure(stock);
        clock.advance(Duration.ofSeconds(59));
        service.ensure(stock);
        verify(data, times(1)).fetchPriceLimits(any());
        clock.advance(Duration.ofSeconds(1));
        service.ensure(stock);
        verify(data, times(2)).fetchPriceLimits(any());
    }

    @Test
    void 거래일이_바뀌면_실패대기를_초기화한다() {
        when(data.fetchPriceLimits(any())).thenThrow(new IllegalStateException());
        service.ensure(stock);
        clock.setCurrent(Instant.parse("2026-09-14T01:00:00Z"));
        service.ensure(stock);
        verify(data, times(2)).fetchPriceLimits(any());
    }

    @Test
    void 과거날짜나_미래시각_응답은_저장하지_않는다() {
        when(data.fetchPriceLimits(any())).thenReturn(limits("2026-09-10T10:00:00+09:00"));
        service.ensure(stock);
        clock.advance(Duration.ofMinutes(5));
        when(data.fetchPriceLimits(any())).thenReturn(limits("2026-09-11T11:00:00+09:00"));
        service.ensure(stock);
        verifyNoInteractions(persistence);
    }

    @Test
    void 국내_null은_실패로_대기하고_가짜시세행을_만들지_않는다() {
        when(data.fetchPriceLimits(any())).thenReturn(new PriceLimits(clock.instant().atOffset(ZoneOffset.UTC), null, null, "KRW"));
        service.ensure(stock);
        service.ensure(stock);
        verify(data, times(1)).fetchPriceLimits(any());
        verifyNoInteractions(persistence);
    }

    @Test
    void 시세행이_없으면_외부요청도_생략한다() {
        when(quotes.findById(1L)).thenReturn(Optional.empty());
        service.ensure(stock);
        verifyNoInteractions(data, persistence);
    }

    @ParameterizedTest
    @CsvSource({"0,70,KRW", "130,-1,KRW", "70,130,KRW", "130.00001,70,KRW",
            "1000000000000000,70,KRW", "130,70,USD"})
    void 잘못된_가격과_통화는_저장하지_않는다(String upper, String lower, String currency) {
        when(data.fetchPriceLimits(any())).thenReturn(new PriceLimits(
                clock.instant().atOffset(ZoneOffset.UTC), new BigDecimal(upper), new BigDecimal(lower), currency));
        service.ensure(stock);
        verifyNoInteractions(persistence);
    }

    @Test
    void 수집중_날짜가_바뀌면_응답을_저장하지_않는다() {
        when(data.fetchPriceLimits(any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofDays(1));
            return limits("2026-09-11T09:00:00+09:00");
        });
        service.ensure(stock);
        verifyNoInteractions(persistence);
    }

    @Test
    void 같은종목의_진행중_요청은_중복호출하지_않는다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(data.fetchPriceLimits(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException();
            return limits("2026-09-11T09:00:00+09:00");
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> first = executor.submit(() -> service.ensure(stock));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                service.ensure(stock);
                verify(data, times(1)).fetchPriceLimits(any());
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 장중_과거값은_숨기고_장후_같은시세날짜만_표시한다() {
        when(quote.getPriceLimitDate()).thenReturn(DATE.minusDays(1));
        assertThat(service.canDisplay(stock, quote)).isFalse();
        clock.setCurrent(Instant.parse("2026-09-11T08:00:00Z"));
        when(quote.getQuoteAt()).thenReturn(OffsetDateTime.parse("2026-09-11T15:30:00+09:00"));
        when(days.quoteTradeDate(any(), any())).thenReturn(Optional.of(DATE));
        assertThat(service.canDisplay(stock, quote)).isFalse();
        when(quote.getPriceLimitDate()).thenReturn(DATE);
        assertThat(service.canDisplay(stock, quote)).isTrue();
    }

    @Test
    void 상세는_게이트에서_기다리지_않고_거절후에도_실패대기를_남기지_않는다() {
        AtomicLong nanos = new AtomicLong();
        FixedIntervalGate gate = new FixedIntervalGate(2, nanos::get,
                ignored -> { throw new AssertionError("상세 조회가 게이트에서 대기하면 안 됩니다"); });
        PriceLimitLoadService displayService = new PriceLimitLoadService(data, days, quotes, persistence, clock, true, gate);
        assertThat(gate.tryAcquire()).isTrue();
        displayService.ensureForDisplay(stock);
        verifyNoInteractions(data, persistence);
        nanos.set(TimeUnit.MILLISECONDS.toNanos(500));
        displayService.ensureForDisplay(stock);
        verify(data).fetchPriceLimits("005930");
        verify(persistence).save(eq(1L), eq(DATE), any());
    }

    @Test
    void 상세의_실제_API실패는_1분대기를_유지한다() {
        AtomicLong nanos = new AtomicLong();
        FixedIntervalGate gate = new FixedIntervalGate(2, nanos::get,
                ignored -> { throw new AssertionError("상세 조회 대기 금지"); });
        PriceLimitLoadService displayService = new PriceLimitLoadService(data, days, quotes, persistence, clock, true, gate);
        when(data.fetchPriceLimits(any())).thenThrow(new IllegalStateException());
        displayService.ensureForDisplay(stock);
        nanos.set(TimeUnit.SECONDS.toNanos(2));
        clock.advance(Duration.ofSeconds(59));
        displayService.ensureForDisplay(stock);
        verify(data, times(1)).fetchPriceLimits(any());
        clock.advance(Duration.ofSeconds(1));
        displayService.ensureForDisplay(stock);
        verify(data, times(2)).fetchPriceLimits(any());
    }

    @Test
    void 배경수집은_게이트를_기다린_뒤_요청한다() {
        AtomicLong nanos = new AtomicLong();
        AtomicLong slept = new AtomicLong();
        FixedIntervalGate gate = new FixedIntervalGate(2, nanos::get, delay -> {
            slept.addAndGet(delay);
            nanos.addAndGet(delay);
        });
        PriceLimitLoadService background = new PriceLimitLoadService(data, days, quotes, persistence, clock, true, gate);
        assertThat(gate.tryAcquire()).isTrue();
        background.ensure(stock);
        assertThat(slept.get()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(500));
        verify(data).fetchPriceLimits("005930");
    }
}
