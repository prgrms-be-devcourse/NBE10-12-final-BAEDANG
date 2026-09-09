package com.baedang.market.service;

import com.baedang.market.config.QuoteCollectionProperties;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QuoteSnapshotLoadServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-09T01:00:00Z");
    private final StockRepository stocks = mock(StockRepository.class);
    private final QuoteSnapshotRepository snapshots = mock(QuoteSnapshotRepository.class);
    private final QuoteRefreshCoordinator coordinator = mock(QuoteRefreshCoordinator.class);
    private final QuoteSnapshotLoadService service = new QuoteSnapshotLoadService(stocks, snapshots, coordinator,
            new QuoteCollectionProperties(Duration.ofSeconds(5), Duration.ofSeconds(20), 3, 1000, 6),
            Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

    @Test
    void 제출실패하면_커서유지하고_성공한_200개_뒤부터_이어간다() {
        when(coordinator.canSubmitBackground()).thenReturn(true);
        List<Stock> page = LongStream.rangeClosed(1, 200).mapToObj(this::stock).toList();
        when(stocks.findQuoteTargets(eq(MarketCountry.KR), eq(0L), any(), any())).thenReturn(page);
        when(coordinator.submitBackground(anyList(), any())).thenReturn(false, true);
        assertThat(service.syncQuotes(MarketCountry.KR, NOW.plusSeconds(60))).isZero();
        assertThat(service.syncQuotes(MarketCountry.KR, NOW.plusSeconds(60))).isEqualTo(200);
        service.syncQuotes(MarketCountry.KR, NOW.plusSeconds(60));
        verify(stocks).findQuoteTargets(eq(MarketCountry.KR), eq(200L), any(), any());
    }

    @Test
    void 우선대상이_끝없이_있어도_네번째는_전체순환한다() {
        when(coordinator.canSubmitBackground()).thenReturn(true);
        List<Stock> page = LongStream.rangeClosed(1, 200).mapToObj(this::stock).toList();
        when(stocks.findQuoteTargets(eq(MarketCountry.KR), anyLong(), any(), any()))
                .thenReturn(page);
        when(coordinator.submitBackground(anyList(), any())).thenReturn(true);
        for (int i = 0; i < 4; i++) service.syncQuotes(MarketCountry.KR, NOW.plusSeconds(60));
        verify(stocks).findQuoteTargets(eq(MarketCountry.KR), eq(0L), any(), any());
    }

    @Test
    void 응답완료가_조금_늦어도_다음_우선순회에서_갱신을_건너뛰지_않는다() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        QuoteSnapshotLoadService timedService = new QuoteSnapshotLoadService(stocks, snapshots, coordinator,
                new QuoteCollectionProperties(Duration.ofSeconds(5),
                        Duration.ofSeconds(20), 3, 1000, 8), clock, new SimpleMeterRegistry());
        Stock stock = stock(1);
        when(coordinator.canSubmitBackground()).thenReturn(true);
        when(coordinator.submitBackground(anyList(), any())).thenReturn(true);
        when(stocks.findQuoteTargets(eq(MarketCountry.KR), eq(0L), any(), any()))
                .thenReturn(List.of(stock));
        timedService.syncQuotes(MarketCountry.KR, NOW.plusSeconds(60));

        QuoteSnapshot completed = mock(QuoteSnapshot.class);
        when(completed.getStockId()).thenReturn(1L);
        when(completed.getCollectedAt()).thenReturn(NOW.plusSeconds(1).atOffset(ZoneOffset.UTC));
        when(snapshots.findByStockIdIn(List.of(1L))).thenReturn(List.of(completed));
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        timedService.syncQuotes(MarketCountry.KR, NOW.plusSeconds(60));

        verify(coordinator, times(2)).submitBackground(List.of(stock), NOW.plusSeconds(60));
    }

    @Test
    void 배경용량이_없으면_DB_대상을_반복조회하지_않는다() {
        when(coordinator.canSubmitBackground()).thenReturn(false);
        assertThat(service.syncQuotes(MarketCountry.KR, NOW.plusSeconds(60))).isZero();
        verifyNoInteractions(stocks, snapshots);
    }

    @Test
    void 장이_닫혔으면_조회하지_않는다() {
        service.syncQuotes(MarketCountry.KR, NOW);
        verifyNoInteractions(stocks, snapshots, coordinator);
    }

    private Stock stock(long id) {
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(id);
        return stock;
    }
}
