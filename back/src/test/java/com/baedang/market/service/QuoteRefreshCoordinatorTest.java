package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.config.QuoteCollectionProperties;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.Stock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuoteRefreshCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-09T01:00:00Z");
    private final MarketDataPort data = mock(MarketDataPort.class);
    private final QuoteSnapshotPersistenceService persistence = mock(QuoteSnapshotPersistenceService.class);
    private final QuoteSnapshotRepository snapshots = mock(QuoteSnapshotRepository.class);
    private final List<Runnable> tasks = new ArrayList<>();
    private final QuoteRefreshCoordinator coordinator = new QuoteRefreshCoordinator(data, persistence, snapshots,
            tasks::add, new QuoteCollectionProperties(Duration.ofSeconds(5),
            Duration.ofSeconds(2), 1, 200, 6), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

    @Test
    void 같은종목_배경요청과_사용자요청은_한_HTTP를_공유한다() throws Exception {
        Stock stock = stock(1);
        QuoteSnapshot quote = quote(1, NOW);
        when(data.fetchPrices(List.of("S1"))).thenReturn(List.of(price("S1")));
        when(snapshots.findById(1L)).thenReturn(Optional.of(quote));
        assertThat(coordinator.submitBackground(List.of(stock), NOW.plusSeconds(10))).isTrue();
        java.util.concurrent.atomic.AtomicReference<Thread> waiter = new java.util.concurrent.atomic.AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task);
            waiter.set(thread);
            return thread;
        })) {
            Future<QuoteSnapshot> future = executor.submit(() -> coordinator.refresh(stock));
            // refresh가 공유 future에서 기다리는 동안 배경 요청을 완료합니다.
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                    .until(() -> waiter.get() != null && waiter.get().getState() == Thread.State.TIMED_WAITING);
            tasks.getFirst().run();
            assertThat(future.get(1, TimeUnit.SECONDS)).isSameAs(quote);
        }
        verify(data, times(1)).fetchPrices(List.of("S1"));
    }

    @Test
    void 배경슬롯이_차도_다른종목_우선요청은_실행하고_배경큐를_쌓지_않는다() {
        coordinator.submitBackground(List.of(stock(1)), NOW.plusSeconds(10));
        assertThat(coordinator.submitBackground(List.of(stock(2)), NOW.plusSeconds(10))).isFalse();
        when(snapshots.findById(3L)).thenReturn(Optional.of(quote(3, NOW)));
        coordinator.refresh(stock(3));
        verify(data).fetchPrices(List.of("S3"));
        assertThat(tasks).hasSize(1);
    }

    @Test
    void 신선한_시세는_재사용하고_조회후에도_오래된_원본이면_거절한다() {
        Stock stock = stock(1);
        when(snapshots.findById(1L)).thenReturn(Optional.of(quote(1, NOW)));
        assertThat(coordinator.requireFresh(stock, Duration.ofSeconds(15))).isNotNull();
        verifyNoInteractions(data);
        when(snapshots.findById(1L)).thenReturn(Optional.of(quote(1, NOW.minusSeconds(16))));
        assertThatThrownBy(() -> coordinator.requireFresh(stock, Duration.ofSeconds(15)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STALE_QUOTE));
    }

    @Test
    void 조회실패는_다음요청의_재시도를_막지_않고_진행상태를_정리한다() {
        Stock stock = stock(1);
        when(data.fetchPrices(List.of("S1"))).thenThrow(new IllegalStateException()).thenReturn(List.of());
        assertThatThrownBy(() -> coordinator.refresh(stock)).isInstanceOf(BusinessException.class);
        assertThat(coordinator.refresh(stock)).isNull();
        verify(data, times(2)).fetchPrices(List.of("S1"));
    }

    @Test
    void 배치상한과_진행종목상한을_지킨다() {
        List<Stock> page = LongStream.rangeClosed(1, 200).mapToObj(this::stock).toList();
        coordinator.submitBackground(page, NOW.plusSeconds(10));
        assertThatThrownBy(() -> coordinator.refresh(stock(201)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOSS_RATE_LIMITED));
        List<Stock> oversized = new ArrayList<>(page);
        oversized.add(stock(201));
        assertThatThrownBy(() -> coordinator.submitBackground(oversized, NOW.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Stock stock(long id) {
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(id);
        when(stock.getSymbol()).thenReturn("S" + id);
        return stock;
    }
    private QuoteSnapshot quote(long id, Instant at) {
        return new QuoteSnapshot(id, BigDecimal.ONE, "KRW", at.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
    }
    private PriceQuote price(String symbol) {
        return new PriceQuote(symbol, BigDecimal.ONE, NOW.atOffset(ZoneOffset.UTC), "KRW");
    }
}
