package com.baedang.stock.service;

import com.baedang.market.entity.MinuteCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.repository.MinuteCandleBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MinuteCandlePersistenceService {

    private final MinuteCandleBatchRepository minuteCandleBatchRepository;

    public MinuteCandlePersistenceService(MinuteCandleBatchRepository minuteCandleBatchRepository) {
        this.minuteCandleBatchRepository = minuteCandleBatchRepository;
    }

    @Transactional
    public void upsert(Long stockId, List<Candle> candles) {
        List<MinuteCandle> rows = candles.stream()
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
