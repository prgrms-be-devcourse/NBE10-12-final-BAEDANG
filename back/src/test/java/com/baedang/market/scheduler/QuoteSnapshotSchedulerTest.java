package com.baedang.market.scheduler;

import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.service.QuoteSnapshotLoadService;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class QuoteSnapshotSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-28T09:30:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private QuoteSnapshotLoadService quoteSnapshotLoadService;

    @Mock
    private MarketSessionProvider marketSessionProvider;

    @Test
    @DisplayName("장이 열려있는 시장만 시세를 동기화")
    void t1() {
        QuoteSnapshotScheduler scheduler = new QuoteSnapshotScheduler(
                quoteSnapshotLoadService,
                marketSessionProvider,
                clock
        );

        when(marketSessionProvider.isOpen(MarketCountry.KR, NOW)).thenReturn(true);
        when(marketSessionProvider.isOpen(MarketCountry.US, NOW)).thenReturn(false);

        scheduler.pollQuotes();

        verify(quoteSnapshotLoadService).syncQuotes(MarketCountry.KR);
        verify(quoteSnapshotLoadService, never()).syncQuotes(MarketCountry.US);
    }

    @Test
    @DisplayName("모든 시장이 닫혀있으면 시세 동기화를 호출하지 않는다")
    void t2() {
        QuoteSnapshotScheduler scheduler = new QuoteSnapshotScheduler(
                quoteSnapshotLoadService,
                marketSessionProvider,
                clock
        );

        when(marketSessionProvider.isOpen(MarketCountry.KR, NOW)).thenReturn(false);
        when(marketSessionProvider.isOpen(MarketCountry.US, NOW)).thenReturn(false);

        scheduler.pollQuotes();

        verifyNoInteractions(quoteSnapshotLoadService);
    }

}
