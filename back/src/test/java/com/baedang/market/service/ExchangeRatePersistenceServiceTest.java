package com.baedang.market.service;

import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.repository.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExchangeRatePersistenceServiceTest {
    private static final OffsetDateTime RATE_AT = OffsetDateTime.parse("2026-08-26T06:00:00Z");
    private static final OffsetDateTime COLLECTED_AT = OffsetDateTime.parse("2026-08-26T06:00:05Z");

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    private ExchangeRatePersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new ExchangeRatePersistenceService(exchangeRateRepository);
    }

    @Test
    @DisplayName("유효한 환율을 INSERT 한다")
    void t1() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "usd",
                "krw",
                new BigDecimal("1400.25"),
                new BigDecimal("1398.50"),
                RATE_AT,
                RATE_AT.plusHours(1)
        );

        when(exchangeRateRepository.insertIgnoreDuplicate(
                "USD", "KRW", quote.rate(), quote.midRate(), RATE_AT, COLLECTED_AT
        )).thenReturn(1);

        boolean inserted = persistenceService.saveIfValid(quote, COLLECTED_AT);

        assertThat(inserted).isTrue();
        verify(exchangeRateRepository).insertIgnoreDuplicate(
                "USD", "KRW", quote.rate(), quote.midRate(), RATE_AT, COLLECTED_AT);
    }

    @Test
    @DisplayName("이미 적재된 rateAt이면 중복 저장하지 않는다")
    void t2() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "USD",
                "KRW",
                new BigDecimal("1400.25"),
                new BigDecimal("1398.50"),
                RATE_AT,
                RATE_AT.plusHours(1)
        );

        when(exchangeRateRepository.insertIgnoreDuplicate(
                "USD", "KRW", quote.rate(), quote.midRate(), RATE_AT, COLLECTED_AT
        )).thenReturn(0);

        boolean inserted = persistenceService.saveIfValid(quote, COLLECTED_AT);

        assertThat(inserted).isFalse();
    }

    @Test
    @DisplayName("rateAt이 없으면 저장하지 않는다")
    void t3() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "USD",
                "KRW",
                new BigDecimal("1400.25"),
                new BigDecimal("1398.50"),
                null, null
        );

        boolean inserted = persistenceService.saveIfValid(quote, COLLECTED_AT);

        assertThat(inserted).isFalse();
        verifyNoInteractions(exchangeRateRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0","-1"})
    @DisplayName("midRate가 0 이하이면 저장하지 않는다")
    void t4(String invalidMidRate) {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "USD",
                "KRW",
                new BigDecimal("1400.25"),
                new BigDecimal(invalidMidRate),
                RATE_AT,
                RATE_AT.plusHours(1)
        );

        boolean inserted = persistenceService.saveIfValid(quote, COLLECTED_AT);

        assertThat(inserted).isFalse();
        verifyNoInteractions(exchangeRateRepository);
    }

    @Test
    @DisplayName("midRate가 없어도 rate가 유효하면 저장한다")
    void t5() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "USD",
                "KRW",
                new BigDecimal("1400.25"),
                null,
                RATE_AT,
                RATE_AT.plusHours(1)
        );

        when(exchangeRateRepository.insertIgnoreDuplicate(
                "USD",
                "KRW",
                quote.rate(),
                null,
                RATE_AT,
                COLLECTED_AT
        )).thenReturn(1);

        boolean inserted = persistenceService.saveIfValid(quote, COLLECTED_AT);
        assertThat(inserted).isTrue();
        verify(exchangeRateRepository).insertIgnoreDuplicate(
                "USD",
                "KRW",
                quote.rate(),
                null,
                RATE_AT,
                COLLECTED_AT
        );
    }
}