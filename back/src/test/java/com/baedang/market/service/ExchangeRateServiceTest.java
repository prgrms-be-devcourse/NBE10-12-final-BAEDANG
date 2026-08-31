package com.baedang.market.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.dto.ExchangeRateLatestResponse;
import com.baedang.market.entity.ExchangeRate;
import com.baedang.market.repository.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock
    ExchangeRateRepository exchangeRateRepository;

    // 2026-08-26 15:00 KST 시점 — 이 날의 자정(KST) 경계는 2026-08-26T00:00:00+09:00.
    private static final Instant NOW = Instant.parse("2026-08-26T06:00:00Z");
    private static final OffsetDateTime TODAY_MIDNIGHT_KST =
            OffsetDateTime.parse("2026-08-26T00:00:00+09:00");
    private static final OffsetDateTime COLLECTED_AT = OffsetDateTime.parse("2026-08-26T06:00:00Z");

    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        service = new ExchangeRateService(exchangeRateRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 두 테스트 이상에서 재사용하는 "최신 행" 픽스처. rate와 midRate를 다르게 둬서
     * 응답이 rate가 아니라 midRate를 쓰는지 구분해서 검증할 수 있게 한다. */
    private static ExchangeRate sampleLatest() {
        return new ExchangeRate(
                "USD", "KRW",
                new BigDecimal("1401.500000"), new BigDecimal("1400.000000"),
                OffsetDateTime.parse("2026-08-26T15:00:00+09:00"), COLLECTED_AT);
    }

    @Test
    @DisplayName("최신 환율과 전일 자정 대비 등락을 계산한다")
    void t1_정상_조회() {
        ExchangeRate latest = sampleLatest();
        ExchangeRate reference = new ExchangeRate(
                "USD", "KRW",
                new BigDecimal("1399.500000"), new BigDecimal("1398.000000"),
                TODAY_MIDNIGHT_KST, COLLECTED_AT);

        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.of(latest));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyAndRateAtLessThanEqualOrderByRateAtDesc(
                "USD", "KRW", TODAY_MIDNIGHT_KST))
                .thenReturn(Optional.of(reference));

        ExchangeRateLatestResponse response = service.getLatest("USD", "KRW");

        BigDecimal expectedChangeAmount = new BigDecimal("2.000000");
        BigDecimal expectedChangeRate =
                expectedChangeAmount.divide(new BigDecimal("1398.000000"), 6, RoundingMode.HALF_UP);

        assertThat(response.baseCurrency()).isEqualTo("USD");
        assertThat(response.quoteCurrency()).isEqualTo("KRW");
        // rate는 화면 표시용 midRate여야 한다 — 체결용 rate(1401.5)가 아니라 1400이 나와야 정상.
        assertThat(new BigDecimal(response.rate())).isEqualByComparingTo("1400.000000");
        assertThat(new BigDecimal(response.changeAmount())).isEqualByComparingTo(expectedChangeAmount);
        assertThat(new BigDecimal(response.changeRate())).isEqualByComparingTo(expectedChangeRate);
    }

    @Test
    @DisplayName("최신 환율이 없으면 EXCHANGE_RATE_NOT_FOUND를 던진다")
    void t2_최신_환율_없음() {
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLatest("USD", "KRW"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND));
    }

    @Test
    @DisplayName("전일 자정 기준값이 없으면(서비스 초기 등) 등락을 0으로 보고 최신 환율은 그대로 내려준다")
    void t3_기준값_없음() {
        ExchangeRate latest = sampleLatest();

        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.of(latest));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyAndRateAtLessThanEqualOrderByRateAtDesc(
                "USD", "KRW", TODAY_MIDNIGHT_KST))
                .thenReturn(Optional.empty());

        ExchangeRateLatestResponse response = service.getLatest("USD", "KRW");

        assertThat(new BigDecimal(response.rate())).isEqualByComparingTo("1400.000000");
        assertThat(new BigDecimal(response.changeAmount())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(new BigDecimal(response.changeRate())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("mid_rate가 비어 있으면(nullable 컬럼) 실거래 rate로 대체한다")
    void t4_midRate_없음() {
        // mid_rate가 NULL인 행 — AccountService.displayRate()가 이미 방어하는 것과 같은 상황.
        ExchangeRate latestWithoutMidRate = new ExchangeRate(
                "USD", "KRW",
                new BigDecimal("1401.500000"), null,
                OffsetDateTime.parse("2026-08-26T15:00:00+09:00"), COLLECTED_AT);

        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.of(latestWithoutMidRate));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyAndRateAtLessThanEqualOrderByRateAtDesc(
                "USD", "KRW", TODAY_MIDNIGHT_KST))
                .thenReturn(Optional.empty());

        ExchangeRateLatestResponse response = service.getLatest("USD", "KRW");

        // NPE 없이, mid_rate 대신 rate(1401.5)로 대체돼야 한다.
        assertThat(new BigDecimal(response.rate())).isEqualByComparingTo("1401.500000");
    }

    @Test
    @DisplayName("base/quote는 대소문자를 가리지 않고 대문자로 정규화해서 조회한다")
    void t5_대소문자_정규화() {
        ExchangeRate latest = sampleLatest();

        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByRateAtDesc("USD", "KRW"))
                .thenReturn(Optional.of(latest));
        when(exchangeRateRepository.findTopByBaseCurrencyAndQuoteCurrencyAndRateAtLessThanEqualOrderByRateAtDesc(
                "USD", "KRW", TODAY_MIDNIGHT_KST))
                .thenReturn(Optional.empty());

        // 소문자로 넘겨도 대문자로 저장된 행과 매치돼야 한다.
        ExchangeRateLatestResponse response = service.getLatest("usd", "krw");

        assertThat(response.baseCurrency()).isEqualTo("USD");
        assertThat(response.quoteCurrency()).isEqualTo("KRW");
    }
}
