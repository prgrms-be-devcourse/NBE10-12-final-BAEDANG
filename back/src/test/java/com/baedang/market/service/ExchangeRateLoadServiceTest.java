package com.baedang.market.service;

import com.baedang.market.port.ExchangeRateQuote;
import com.baedang.market.port.MarketCalendarPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExchangeRateLoadServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-26T06:00:00Z");

    private static final OffsetDateTime COLLECTED_AT =
            NOW.atOffset(ZoneOffset.UTC);

    @Mock
    private MarketCalendarPort marketCalendarPort;

    @Mock
    private ExchangeRatePersistenceService persistenceService;

    private ExchangeRateLoadService loadService;

    @BeforeEach
    void setUp() {
        loadService = new ExchangeRateLoadService(
                marketCalendarPort,
                persistenceService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("외부 환율을 조회하고 고정된 수집 시각으로 적재한다")
    void t1() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "USD",
                "KRW",
                new BigDecimal("1400"),
                new BigDecimal("1398"),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC)
        );

        when(marketCalendarPort.fetchExchangeRate()).thenReturn(quote);

        when(persistenceService.saveIfValid(quote, COLLECTED_AT)).thenReturn(true);

        boolean inserted = loadService.syncExchangeRate();

        assertThat(inserted).isTrue();

        verify(persistenceService).saveIfValid(quote, COLLECTED_AT);
    }

    @Test
    @DisplayName("외부 환율 응답이 null이면 적재하지 않는다")
    void t2() {
        when(marketCalendarPort.fetchExchangeRate()).thenReturn(null);

        boolean inserted = loadService.syncExchangeRate();

        assertThat(inserted).isFalse();
        verifyNoInteractions(persistenceService);
    }
}
