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

        when(marketSessionProvider.currentSession(MarketCountry.KR, NOW)).thenReturn(new com.baedang.market.port.MarketSessionStatus(true, NOW.plusSeconds(60)));
        when(marketSessionProvider.currentSession(MarketCountry.US, NOW)).thenReturn(new com.baedang.market.port.MarketSessionStatus(false, NOW));

        scheduler.pollQuotes();

        verify(quoteSnapshotLoadService).syncQuotes(MarketCountry.KR, NOW.plusSeconds(60));
        verify(quoteSnapshotLoadService, never()).syncQuotes(eq(MarketCountry.US), any());
    }

    @Test
    @DisplayName("모든 시장이 닫혀있으면 시세 동기화를 호출하지 않는다")
    void t2() {
        QuoteSnapshotScheduler scheduler = new QuoteSnapshotScheduler(
                quoteSnapshotLoadService,
                marketSessionProvider,
                clock
        );

        when(marketSessionProvider.currentSession(MarketCountry.KR, NOW)).thenReturn(new com.baedang.market.port.MarketSessionStatus(false, NOW));
        when(marketSessionProvider.currentSession(MarketCountry.US, NOW)).thenReturn(new com.baedang.market.port.MarketSessionStatus(false, NOW));

        scheduler.pollQuotes();

        verifyNoInteractions(quoteSnapshotLoadService);
    }

}
