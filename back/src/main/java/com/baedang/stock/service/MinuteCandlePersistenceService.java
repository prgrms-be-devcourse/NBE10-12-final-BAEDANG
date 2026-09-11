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

import java.time.Instant;
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
                    Instant at = candle.candleAt().toInstant();
                    LocalDate date = at.atZone(country.zoneId()).toLocalDate();
                    MarketCalendarDay day = calendars.computeIfAbsent(date, value -> tradingDays.calendar(country, value));
                    // 봉 시각은 시작 시각이므로 마감 시각에 시작하는 봉은 장외에 속한다.
                    return day.isRegularSessionAt(at);
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
