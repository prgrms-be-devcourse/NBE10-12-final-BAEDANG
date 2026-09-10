package com.baedang.stock.service;

import com.baedang.market.entity.MinuteCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.repository.MinuteCandleBatchRepository;
import com.baedang.market.service.MarketTradingDayPolicy;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinuteCandlePersistenceServiceTest {
    @Test void excludePreAndPostMarketBarsIncludingCloseBoundary() {
        var calendars = mock(MarketCalendarPort.class);
        var repository = mock(MinuteCandleBatchRepository.class);
        var service = new MinuteCandlePersistenceService(repository,new MarketTradingDayPolicy(calendars));
        var open = OffsetDateTime.parse("2026-11-27T09:30:00-05:00");
        var close = OffsetDateTime.parse("2026-11-27T13:00:00-05:00");
        when(calendars.fetchUsMarketCalendar(open.toLocalDate())).thenReturn(new MarketCalendarDay(MarketCountry.US,open.toLocalDate(),true,open,close,null));
        service.upsert(1L,MarketCountry.US,List.of(candle(open.minusMinutes(1)),candle(open),candle(close.minusMinutes(1)),candle(close)));
        ArgumentCaptor<List<MinuteCandle>> rows = ArgumentCaptor.captor();
        verify(repository).upsertAll(rows.capture());
        assertThat(rows.getValue()).extracting(MinuteCandle::getCandleAt).containsExactly(open,close.minusMinutes(1));
        verify(calendars,times(1)).fetchUsMarketCalendar(open.toLocalDate());
    }
    private Candle candle(OffsetDateTime at) {
        return new Candle(at,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,"USD");
    }
}
