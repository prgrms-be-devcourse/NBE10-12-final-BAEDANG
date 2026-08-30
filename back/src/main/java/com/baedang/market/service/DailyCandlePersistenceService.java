package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.DailyCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.repository.DailyCandleBatchRepository;
import com.baedang.stock.entity.MarketCountry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 일봉 데이터를 검증하고 KST 기준 날짜로 변환하여 daily_candle 테이블에 저장하는 서비스.
 */
@Service
public class DailyCandlePersistenceService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DailyCandleBatchRepository dailyCandleBatchRepository;

    public DailyCandlePersistenceService(DailyCandleBatchRepository dailyCandleBatchRepository) {
        this.dailyCandleBatchRepository = dailyCandleBatchRepository;
    }

    /** 일봉 목록을 KST 기준 날짜로 변환하여 저장합니다. */
    @Transactional
    public void upsert(Long stockId, MarketCountry marketCountry, String stockCurrency, List<Candle> candles) {
        if (candles == null) {
            throw invalidCandle(stockId, "candles=null");
        }
        if (candles.isEmpty()) return;
        if (stockId == null || marketCountry == null) {
            throw invalidCandle(stockId, "종목 식별 정보가 비어 있음");
        }
        validateCurrency(stockId, stockCurrency, candles);

        Set<LocalDate> tradeDates = new HashSet<>();
        List<DailyCandle> rows = candles.stream()
                .map(candle -> toRow(stockId, candle, tradeDates))
                .toList();

        dailyCandleBatchRepository.upsertAll(rows);
    }

    /** 토스 응답 통화와 종목 통화 일치 여부 검증 */
    private void validateCurrency(Long stockId, String stockCurrency, List<Candle> candles) {
        boolean mismatch = candles.stream().anyMatch(candle ->
                candle == null
                        || candle.currency() == null
                        || candle.currency().isBlank()
                        || stockCurrency == null
                        || stockCurrency.isBlank()
                        || !stockCurrency.equalsIgnoreCase(candle.currency().trim()));
        if (mismatch) {
            throw new BusinessException(
                    ErrorCode.QUOTE_CURRENCY_MISMATCH,
                    "stockId=" + stockId);
        }
    }

    private DailyCandle toRow(
            Long stockId,
            Candle candle,
            Set<LocalDate> tradeDates
    ) {
        validateValues(stockId, candle);
        LocalDate tradeDate = candle.candleAt().atZoneSameInstant(KST).toLocalDate();
        if (!tradeDates.add(tradeDate)) {
            throw invalidCandle(stockId, "중복 거래일=" + tradeDate);
        }
        return new DailyCandle(
                stockId,
                tradeDate,
                candle.openPrice(),
                candle.highPrice(),
                candle.lowPrice(),
                candle.closePrice(),
                candle.volume());
    }

    private void validateValues(Long stockId, Candle candle) {
        if (candle.candleAt() == null
                || !positive(candle.openPrice())
                || !positive(candle.highPrice())
                || !positive(candle.lowPrice())
                || !positive(candle.closePrice())) {
            throw invalidCandle(stockId, "캔들 시각 또는 OHLC가 올바르지 않음");
        }
        if (candle.highPrice().compareTo(candle.openPrice()) < 0
                || candle.highPrice().compareTo(candle.closePrice()) < 0
                || candle.highPrice().compareTo(candle.lowPrice()) < 0
                || candle.lowPrice().compareTo(candle.openPrice()) > 0
                || candle.lowPrice().compareTo(candle.closePrice()) > 0) {
            throw invalidCandle(stockId, "OHLC 범위가 올바르지 않음");
        }
        if (candle.volume() != null
                && (candle.volume().signum() < 0 || candle.volume().stripTrailingZeros().scale() > 0)) {
            throw invalidCandle(stockId, "거래량이 음수이거나 정수가 아님");
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BusinessException invalidCandle(Long stockId, String reason) {
        return new BusinessException(ErrorCode.TOSS_API_ERROR, "stockId=" + stockId + ", " + reason);
    }
}
