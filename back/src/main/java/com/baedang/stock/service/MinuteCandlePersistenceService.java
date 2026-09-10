package com.baedang.stock.service;

import com.baedang.market.entity.MinuteCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.repository.MinuteCandleBatchRepository;
import com.baedang.market.service.MarketTradingDayPolicy;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MinuteCandlePersistenceService {

    private final MarketTradingDayPolicy tradingDays;
    private final MinuteCandleBatchRepository minuteCandleBatchRepository;

    public MinuteCandlePersistenceService(MinuteCandleBatchRepository minuteCandleBatchRepository, MarketTradingDayPolicy tradingDays) {
        this.tradingDays = tradingDays;
        this.minuteCandleBatchRepository = minuteCandleBatchRepository;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void upsert(Long stockId, MarketCountry country, List<Candle> candles) {
        Map<LocalDate, MarketCalendarDay> calendars = new HashMap<>();
        List<MinuteCandle> rows = candles.stream()
                .filter(candle -> {
                    var at = candle.candleAt().toInstant();
                    var date = at.atZone(country.zoneId()).toLocalDate();
                    var day = calendars.computeIfAbsent(date, value -> tradingDays.calendar(country, value));
                    // Bar timestamps are opening instants: a bar at close belongs to extended hours.
                    return day.isOpen() && day.regularOpenAt() != null && day.regularCloseAt() != null
                            && !at.isBefore(day.regularOpenAt().toInstant())
                            && at.isBefore(day.regularCloseAt().toInstant());
                })
                .map(candle -> new MinuteCandle(
                        stockId,
                        candle.candleAt(),
                        candle.openPrice(),
                        candle.highPrice(),
                        candle.lowPrice(),
                        candle.closePrice(),
                        candle.volume()))
                .toList();
        minuteCandleBatchRepository.upsertAll(rows);
    }
}
