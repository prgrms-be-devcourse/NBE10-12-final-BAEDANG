package com.baedang.market.service;

import com.baedang.market.entity.*;
import com.baedang.market.port.*;
import com.baedang.market.repository.*;
import com.baedang.stock.entity.*;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrevCloseUpdateServiceTest {
    final StockRepository stocks = mock(StockRepository.class);
    final QuoteSnapshotRepository snapshots = mock(QuoteSnapshotRepository.class);
    final DailyCandleRepository candles = mock(DailyCandleRepository.class);
    final LatestCompletedTradingDayResolver completed = mock(LatestCompletedTradingDayResolver.class);
    final MarketTradingDayPolicy policy = mock(MarketTradingDayPolicy.class);
    final MarketDataPort data = mock(MarketDataPort.class);
    final DailyCandlePersistenceService daily = mock(DailyCandlePersistenceService.class);
    final QuoteSnapshotPersistenceService prices = mock(QuoteSnapshotPersistenceService.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-09-12T05:00:00Z"), ZoneOffset.UTC);
    final PrevCloseUpdateService service = new PrevCloseUpdateService(stocks, snapshots, completed, policy, data, daily, prices, clock, new DailyCandleFetchCoordinator());
    final LocalDate friday = LocalDate.of(2026, 9, 11);
    final Stock stock = Stock.create("AAPL", MarketCountry.US, "NASDAQ", "Apple", null, "USD", "STOCK", true);
    final OffsetDateTime closeAt = OffsetDateTime.parse("2026-09-11T16:00:00-04:00");

    void setup() {
        ReflectionTestUtils.setField(stock, "stockId", 1L);
        when(completed.resolve(MarketCountry.US)).thenReturn(Optional.of(friday));
        when(policy.previousTradingDay(MarketCountry.US, friday)).thenReturn(Optional.of(friday.minusDays(1)));
        when(daily.upsert(any(), any(), any(), any(), any())).thenReturn(List.of(candle(friday, "110"), candle(friday.minusDays(1), "100")));
        when(policy.calendar(MarketCountry.US, friday)).thenReturn(new MarketCalendarDay(MarketCountry.US, friday, true, closeAt.minusHours(6).minusMinutes(30), closeAt, null));
    }
    @Test void weekendRecoveryUsesFridayCloseWithoutMovingReferenceToFriday() {
        setup();
        service.recover(stock);
        verify(prices).saveRecoveredClose(eq(stock), isNull(), any(), any(), any());
        verify(data).fetchCandles("AAPL", CandleInterval.ONE_DAY, 200);
    }
    @Test void repeatedRecoveryDoesNotRewriteVerifiedClose() {
        setup();
        var quote = new QuoteSnapshot(null, new BigDecimal("110"), "USD", closeAt, closeAt);
        quote.applyReference(friday.minusDays(1), new BigDecimal("100"));
        when(snapshots.findById(1L)).thenReturn(Optional.of(quote));
        when(policy.quoteTradeDate(MarketCountry.US, closeAt.toInstant())).thenReturn(Optional.of(friday));
        assertThat(service.recover(stock)).isFalse();
        verify(prices, never()).saveOrUpdate(any(), any(), any());
        verifyNoInteractions(data);
    }
    @Test void unavailableCalendarNeverCopiesLastPrice() {
        ReflectionTestUtils.setField(stock, "stockId", 1L);
        when(completed.resolve(MarketCountry.US)).thenReturn(Optional.empty());
        assertThat(service.recover(stock)).isFalse();
        verifyNoInteractions(prices, data, candles);
    }
    private DailyCandle candle(LocalDate date, String value) {
        var p = new BigDecimal(value);
        return new DailyCandle(null, date, p, p, p, p, BigDecimal.ONE);
    }
}
