package com.baedang.stock.service;

import com.baedang.market.entity.DailyCandle;
import com.baedang.market.entity.MinuteCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.repository.MinuteCandleRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CandleQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T01:00:00Z");

    @Mock StockRepository stockRepository;
    @Mock DailyCandleRepository dailyCandleRepository;
    @Mock MinuteCandleRepository minuteCandleRepository;
    @Mock MarketDataPort marketDataPort;
    @Mock MinuteCandlePersistenceService persistenceService;
    @Mock Stock stock;

    private CandleQueryService service;

    @BeforeEach
    void setUp() {
        service = new CandleQueryService(
                new CandleQueryPolicy(),
                stockRepository,
                dailyCandleRepository,
                minuteCandleRepository,
                marketDataPort,
                persistenceService,
                new MinuteCandleFetchCache(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        when(stock.getStockId()).thenReturn(10L);
        when(stock.getSymbol()).thenReturn("005930");
        when(stock.getCurrency()).thenReturn("KRW");
    }

    @Test
    void 일봉은_명세개수로_조회하고_과거부터_정렬해_문자열로_응답한다() {
        DailyCandle recent = daily(LocalDate.of(2026, 8, 28), "110");
        DailyCandle old = daily(LocalDate.of(2026, 8, 27), "100");
        when(dailyCandleRepository.findByStockIdOrderByTradeDateDesc(
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(recent, old));

        var response = service.getCandles("005930", "KR", "1d", "6M");

        assertThat(response.interval()).isEqualTo("1d");
        assertThat(response.range()).isEqualTo("6M");
        assertThat(response.currency()).isEqualTo("KRW");
        assertThat(response.items()).extracting(item -> item.close())
                .containsExactly("100", "110");
        assertThat(response.items().get(0).at().getOffset()).isEqualTo(ZoneOffset.ofHours(9));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(dailyCandleRepository).findByStockIdOrderByTradeDateDesc(
                org.mockito.ArgumentMatchers.eq(10L), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(130);
        verify(marketDataPort, never()).fetchCandles(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 신선한_분봉이_있으면_외부조회없이_최근_200개를_조회한다() {
        MinuteCandle recent = minute(OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC), "110");
        when(minuteCandleRepository.findTopByStockIdOrderByCandleAtDesc(10L))
                .thenReturn(Optional.of(recent));
        when(minuteCandleRepository.findByStockIdOrderByCandleAtDesc(
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(recent));

        var response = service.getCandles("005930", "KR", "1m", "1D");

        assertThat(response.items()).hasSize(1);
        verify(marketDataPort, never()).fetchCandles(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(minuteCandleRepository).findByStockIdOrderByCandleAtDesc(
                org.mockito.ArgumentMatchers.eq(10L), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void 오래된_분봉은_기존_Port로_조회해_저장한_뒤_반환한다() {
        MinuteCandle stale = minute(OffsetDateTime.ofInstant(NOW.minusSeconds(120), ZoneOffset.UTC), "90");
        Candle fetched = new Candle(
                OffsetDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC),
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), new BigDecimal("1000"), "KRW");
        when(minuteCandleRepository.findTopByStockIdOrderByCandleAtDesc(10L))
                .thenReturn(Optional.of(stale));
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_MINUTE, 200))
                .thenReturn(List.of(fetched));
        when(minuteCandleRepository.findByStockIdOrderByCandleAtDesc(
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(minute(fetched.candleAt(), "105")));

        service.getCandles("005930", "KR", "1m", "1D");
        service.getCandles("005930", "KR", "1m", "1D");

        verify(marketDataPort, times(1)).fetchCandles("005930", CandleInterval.ONE_MINUTE, 200);
        verify(persistenceService, times(1)).upsert(10L, List.of(fetched));
    }

    @Test
    void 외부_분봉_통화가_종목시장과_다르면_저장하지_않는다() {
        Candle mismatched = new Candle(
                OffsetDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, "USD");
        when(minuteCandleRepository.findTopByStockIdOrderByCandleAtDesc(10L))
                .thenReturn(Optional.empty());
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_MINUTE, 200))
                .thenReturn(List.of(mismatched));

        assertThatThrownBy(() -> service.getCandles("005930", "KR", "1m", "1D"))
                .isInstanceOf(com.baedang.global.error.BusinessException.class)
                .extracting(exception -> ((com.baedang.global.error.BusinessException) exception).getErrorCode())
                .isEqualTo(com.baedang.global.error.ErrorCode.QUOTE_CURRENCY_MISMATCH);

        verify(persistenceService, never()).upsert(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private DailyCandle daily(LocalDate date, String close) {
        return new DailyCandle(
                10L, date, new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal("1000"));
    }

    private MinuteCandle minute(OffsetDateTime at, String close) {
        return new MinuteCandle(
                10L, at, new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal("1000"));
    }
}
