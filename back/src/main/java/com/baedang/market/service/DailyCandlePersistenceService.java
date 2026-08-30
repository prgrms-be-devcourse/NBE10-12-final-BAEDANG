package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.DailyCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.repository.DailyCandleBatchRepository;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;

/**
 * 일봉 데이터를 검증 및 시장 국가별 현지 거래일자로 변환하여 daily_candle 테이블에 저장하는 서비스.
 */
@Service
public class DailyCandlePersistenceService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final DailyCandleBatchRepository dailyCandleBatchRepository;

    public DailyCandlePersistenceService(DailyCandleBatchRepository dailyCandleBatchRepository) {
        this.dailyCandleBatchRepository = dailyCandleBatchRepository;
    }

    /** 일봉 목록을 시장 국가별 현지 거래일자로 변환하여 저장합니다. */
    @Transactional
    public void upsert(Long stockId, MarketCountry marketCountry, String stockCurrency, List<Candle> candles) {
        if (candles.isEmpty()) return;
        validateCurrency(stockId, stockCurrency, candles);

        ZoneId zoneId = zoneIdFor(marketCountry);
        List<DailyCandle> rows = candles.stream()
                .map(candle -> new DailyCandle(
                        stockId,
                        candle.candleAt().atZoneSameInstant(zoneId).toLocalDate(),
                        candle.openPrice(),
                        candle.highPrice(),
                        candle.lowPrice(),
                        candle.closePrice(),
                        candle.volume()))
                .toList();

        dailyCandleBatchRepository.upsertAll(rows);
    }

    private ZoneId zoneIdFor(MarketCountry marketCountry) {
        return marketCountry == MarketCountry.US ? NY : KST;
    }

    /** 토스 응답 통화와 종목 통화 일치 여부 검증 */
    private void validateCurrency(Long stockId, String stockCurrency, List<Candle> candles) {
        boolean mismatch = candles.stream().anyMatch(candle ->
                candle.currency() == null
                        || stockCurrency == null
                        || !stockCurrency.equalsIgnoreCase(candle.currency().trim()));
        if (mismatch) {
            throw new BusinessException(
                    ErrorCode.QUOTE_CURRENCY_MISMATCH,
                    "stockId=" + stockId);
        }
    }
}
