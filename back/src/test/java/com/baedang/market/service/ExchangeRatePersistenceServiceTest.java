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

        when(exchangeRateRepository.upsertLatestObservation(
                "USD", "KRW", quote.rate(), quote.midRate(), RATE_AT,RATE_AT.plusHours(1), COLLECTED_AT
        )).thenReturn(1);

        boolean inserted = persistenceService.saveIfValid(quote, COLLECTED_AT);

        assertThat(inserted).isTrue();
        verify(exchangeRateRepository).upsertLatestObservation(
                "USD", "KRW", quote.rate(), quote.midRate(), RATE_AT,RATE_AT.plusHours(1), COLLECTED_AT);
    }

    @Test
    @DisplayName("기존 수신 시각보다 오래되면 저장하지 않는다")
    void t2() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "USD",
                "KRW",
                new BigDecimal("1400.25"),
                new BigDecimal("1398.50"),
                RATE_AT,
                RATE_AT.plusHours(1)
        );

        when(exchangeRateRepository.upsertLatestObservation(
                "USD", "KRW", quote.rate(), quote.midRate(), RATE_AT,RATE_AT.plusHours(1), COLLECTED_AT
        )).thenReturn(0);

        boolean inserted = persistenceService.saveIfValid(quote, COLLECTED_AT);

        assertThat(inserted).isFalse();
    }

    @Test
    @DisplayName("validFrom이 없으면 저장하지 않는다")
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
    @ValueSource(strings = {"0", "-1", "1300.1234567", "10000000000000"})
    @DisplayName("midRate가 양수 또는 DB 정밀도 조건을 위반하면 저장하지 않는다")
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

        when(exchangeRateRepository.upsertLatestObservation(
                "USD",
                "KRW",
                quote.rate(),
                null,
                RATE_AT,RATE_AT.plusHours(1),
                COLLECTED_AT
        )).thenReturn(1);

        boolean inserted = persistenceService.saveIfValid(quote, COLLECTED_AT);
        assertThat(inserted).isTrue();
        verify(exchangeRateRepository).upsertLatestObservation(
                "USD",
                "KRW",
                quote.rate(),
                null,
                RATE_AT,RATE_AT.plusHours(1),
                COLLECTED_AT
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "currency", "until", "expired", "future", "inverted", "scale", "overflow"})
    void 잘못된_원본환율은_DB에_반영하지않는다(String kind) {
        OffsetDateTime from = RATE_AT;
        OffsetDateTime until = RATE_AT.plusHours(1);
        BigDecimal rate = new BigDecimal("1400.25");
        switch (kind) {
            case "until" -> until = null;
            case "expired" -> until = COLLECTED_AT;
            case "future" -> from = COLLECTED_AT.plusSeconds(1);
            case "inverted" -> until = RATE_AT.minusSeconds(1);
            case "scale" -> rate = new BigDecimal("1400.1234567");
            case "overflow" -> rate = new BigDecimal("10000000000000");
        }
        ExchangeRateQuote quote = kind.equals("null") ? null : new ExchangeRateQuote(
                kind.equals("currency") ? "EUR" : "USD", "KRW", rate, null, from, until);
        assertThat(persistenceService.saveIfValid(quote, COLLECTED_AT)).isFalse();
        verifyNoInteractions(exchangeRateRepository);
    }
}
