package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.DailyCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.repository.DailyCandleBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;

/**
 * {@link Candle} 목록을 {@code daily_candle} 테이블에 영속합니다.
 *
 * <p>핵심 책임 두 가지:
 * <ol>
 *   <li>통화 검증 — Toss 응답 통화가 종목 원장 통화와 다르면 저장하지 않습니다.</li>
 *   <li>날짜 변환 — {@code candleAt}(UTC)을 KST 기준 {@link java.time.LocalDate}로 바꿔
 *       미국 종목 날짜가 UTC 로 자를 때 하루 밀리는 문제를 방지합니다.</li>
 * </ol>
 */
@Service
public class DailyCandlePersistenceService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DailyCandleBatchRepository dailyCandleBatchRepository;

    public DailyCandlePersistenceService(DailyCandleBatchRepository dailyCandleBatchRepository) {
        this.dailyCandleBatchRepository = dailyCandleBatchRepository;
    }

    /**
     * 주어진 캔들 목록을 종목 ID 에 대한 일봉으로 저장합니다.
     *
     * @param stockId       내부 종목 ID
     * @param stockCurrency 종목 통화 (KRW / USD)
     * @param candles       외부 API 에서 받은 캔들 목록
     */
    @Transactional
    public void upsert(Long stockId, String stockCurrency, List<Candle> candles) {
        if (candles.isEmpty()) return;
        validateCurrency(stockId, stockCurrency, candles);

        List<DailyCandle> rows = candles.stream()
                .map(candle -> new DailyCandle(
                        stockId,
                        candle.candleAt().atZoneSameInstant(KST).toLocalDate(),
                        candle.openPrice(),
                        candle.highPrice(),
                        candle.lowPrice(),
                        candle.closePrice(),
                        candle.volume()))
                .toList();

        dailyCandleBatchRepository.upsertAll(rows);
    }

    /**
     * Toss 응답 통화가 종목 원장 통화와 다를 경우 예외를 던집니다.
     * 잘못된 데이터가 {@code daily_candle} 이나 {@code prev_close} 에 스며드는 것을 막습니다.
     */
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
