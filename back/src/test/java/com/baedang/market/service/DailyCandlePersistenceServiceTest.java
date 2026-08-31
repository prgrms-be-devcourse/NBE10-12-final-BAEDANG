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
    @DisplayName("국내 종목은 KST 기준으로 trade_date 를 변환한다")
    void 국내종목_KST_기준_일자_변환() {
        // UTC 2026-08-28 06:00:00 = KST 2026-08-28 15:00:00
        OffsetDateTime utcTime = OffsetDateTime.of(2026, 8, 28, 6, 0, 0, 0, ZoneOffset.UTC);

        service.upsert(1L, "KRW", List.of(candle(utcTime, "KRW")));

        ArgumentCaptor<List<DailyCandle>> captor = ArgumentCaptor.captor();
        verify(repository).upsertAll(captor.capture());
        assertThat(captor.getValue().get(0).getTradeDate())
                .isEqualTo(LocalDate.of(2026, 8, 28));
    }

    @Test
    @DisplayName("미국 종목도 KST 기준 날짜로 변환한다")
    void 미국종목_KST_기준_일자_변환() {
        // 미국 일봉 timestamp 계약은 봉 시작 시각이다. 09:30 ET는 같은 날 22:30 KST다.
        OffsetDateTime usCandleStart = OffsetDateTime.parse("2026-08-27T09:30:00-04:00");

        service.upsert(1L, "USD", List.of(candle(usCandleStart, "USD")));

        ArgumentCaptor<List<DailyCandle>> captor = ArgumentCaptor.captor();
        verify(repository).upsertAll(captor.capture());
        assertThat(captor.getValue().get(0).getTradeDate())
                .isEqualTo(LocalDate.of(2026, 8, 27));
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

    @Test
    @DisplayName("OHLC 범위가 올바르지 않으면 저장하지 않는다")
    void 잘못된_OHLC를_거절한다() {
        Candle invalid = new Candle(
                OffsetDateTime.now(), new BigDecimal("100"), new BigDecimal("90"),
                new BigDecimal("80"), new BigDecimal("95"), BigDecimal.ONE, "KRW");

        assertThatThrownBy(() -> service.upsert(1L, "KRW", List.of(invalid)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
        verify(repository, never()).upsertAll(any());
    }

    @Test
    @DisplayName("음수 또는 소수 거래량은 저장하지 않는다")
    void 잘못된_거래량을_거절한다() {
        Candle invalid = new Candle(
                OffsetDateTime.now(), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("-1"), "KRW");

        assertThatThrownBy(() -> service.upsert(1L, "KRW", List.of(invalid)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
        verify(repository, never()).upsertAll(any());
    }

    @Test
    @DisplayName("한 응답에 같은 거래일이 중복되면 저장하지 않는다")
    void 중복_거래일을_거절한다() {
        OffsetDateTime at = OffsetDateTime.of(2026, 8, 28, 6, 0, 0, 0, ZoneOffset.UTC);

        assertThatThrownBy(() -> service.upsert(
                1L, "KRW", List.of(candle(at, "KRW"), candle(at.plusHours(1), "KRW"))))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.TOSS_API_ERROR);
        verify(repository, never()).upsertAll(any());
    }

    private Candle candle(OffsetDateTime at, String currency) {
        BigDecimal p = BigDecimal.ONE;
        return new Candle(at, p, p, p, p, p, currency);
    }
}
