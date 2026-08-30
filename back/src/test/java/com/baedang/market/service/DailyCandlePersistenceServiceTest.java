package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.DailyCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.repository.DailyCandleBatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DailyCandlePersistenceServiceTest {

    @Mock
    DailyCandleBatchRepository repository;

    @InjectMocks
    DailyCandlePersistenceService service;

    @Test
    @DisplayName("KST 기준으로 trade_date 를 변환한다 — UTC 날짜와 동일한 경우")
    void KST_날짜가_UTC와_같은_경우_그대로_저장된다() {
        // UTC 2026-08-28 06:00:00 = KST 2026-08-28 15:00:00 (같은 날)
        OffsetDateTime utcTime = OffsetDateTime.of(2026, 8, 28, 6, 0, 0, 0, ZoneOffset.UTC);

        service.upsert(1L, "KRW", List.of(candle(utcTime, "KRW")));

        ArgumentCaptor<List<DailyCandle>> captor = ArgumentCaptor.captor();
        verify(repository).upsertAll(captor.capture());
        assertThat(captor.getValue().get(0).getTradeDate())
                .isEqualTo(LocalDate.of(2026, 8, 28));
    }

    @Test
    @DisplayName("UTC 기준 전날 밤 = KST 당일 새벽인 미국 종목 날짜를 KST 로 변환한다")
    void 미국종목_마감시각_KST_변환시_날짜가_하루_앞당겨진다() {
        // UTC 2026-08-27 20:00:00 = KST 2026-08-28 05:00:00 (미국 장 KST 기준 다음날 새벽 마감)
        // UTC 기준으로 자르면 2026-08-27, KST 기준이면 2026-08-28
        OffsetDateTime usCloseUtc = OffsetDateTime.of(2026, 8, 27, 20, 0, 0, 0, ZoneOffset.UTC);

        service.upsert(1L, "USD", List.of(candle(usCloseUtc, "USD")));

        ArgumentCaptor<List<DailyCandle>> captor = ArgumentCaptor.captor();
        verify(repository).upsertAll(captor.capture());
        assertThat(captor.getValue().get(0).getTradeDate())
                .isEqualTo(LocalDate.of(2026, 8, 28)); // UTC 기준이면 8/27 로 하루 밀림
    }

    @Test
    @DisplayName("통화 불일치 종목은 예외를 던지고 저장하지 않는다")
    void 통화_불일치_캔들은_예외를_던진다() {
        Candle usdCandle = candle(OffsetDateTime.now(), "USD");

        assertThatThrownBy(() -> service.upsert(1L, "KRW", List.of(usdCandle)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUOTE_CURRENCY_MISMATCH);

        verify(repository, never()).upsertAll(any());
    }

    @Test
    @DisplayName("빈 캔들 목록은 저장을 호출하지 않는다")
    void 빈_캔들_목록은_저장을_스킵한다() {
        service.upsert(1L, "KRW", List.of());

        verify(repository, never()).upsertAll(any());
    }

    @Test
    @DisplayName("캔들 currency 가 null 이면 불일치로 처리한다")
    void 캔들_통화가_null이면_예외를_던진다() {
        Candle nullCurrency = new Candle(
                OffsetDateTime.now(), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null);

        assertThatThrownBy(() -> service.upsert(1L, "KRW", List.of(nullCurrency)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUOTE_CURRENCY_MISMATCH);
    }

    private Candle candle(OffsetDateTime at, String currency) {
        BigDecimal p = BigDecimal.ONE;
        return new Candle(at, p, p, p, p, p, currency);
    }
}
