package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyCandleCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T06:40:00Z");

    @Mock MarketDataPort marketDataPort;
    @Mock StockRepository stockRepository;
    @Mock DailyCandlePersistenceService persistenceService;
    @Mock MarketCalendarPort marketCalendarPort;

    /** universeSize=2 로 고정하여 테스트 속도를 높입니다. */
    private DailyCandleCollectionService service() {
        return new DailyCandleCollectionService(
                marketDataPort, stockRepository, persistenceService,
                marketCalendarPort, Clock.fixed(NOW, ZoneOffset.UTC), 2);
    }

    // ── collect ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("수집에 성공한 종목은 persistenceService 에 저장한다")
    void collect_성공한_종목을_저장한다() {
        Stock stock = mockStock(1L, "005930", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(stock));
        allowCollection(MarketCountry.KR);
        List<Candle> candles = List.of(candle("KRW"));
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, 1))
                .thenReturn(candles);

        service().collect(MarketCountry.KR);

        verify(persistenceService).upsert(1L, MarketCountry.KR, "KRW", candles);
    }

    @Test
    @DisplayName("개별 종목 수집 실패는 나머지 종목 수집을 막지 않는다")
    void collect_한_종목_실패해도_나머지는_계속_수집한다() {
        Stock failing = mockStock(1L, "FAIL", "KRW");
        Stock success = mockStock(2L, "OK", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(failing, success));
        allowCollection(MarketCountry.KR);
        when(marketDataPort.fetchCandles("FAIL", CandleInterval.ONE_DAY, 1))
                .thenThrow(new RuntimeException("Toss API 오류"));
        when(marketDataPort.fetchCandles("OK", CandleInterval.ONE_DAY, 1))
                .thenReturn(List.of(candle("KRW")));

        service().collect(MarketCountry.KR);

        verify(persistenceService, times(1)).upsert(eq(2L), eq(MarketCountry.KR), anyString(), any());
        verify(persistenceService, never()).upsert(eq(1L), any(), anyString(), any());
    }

    @Test
    @DisplayName("휴장일이면 캔들 API를 호출하지 않는다")
    void collect_휴장일이면_수집하지_않는다() {
        Stock stock = mockStock(1L, "005930", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(stock));
        LocalDate tradeDate = NOW.atZone(ZoneOffset.ofHours(9)).toLocalDate();
        when(marketCalendarPort.fetchKrMarketCalendar(tradeDate))
                .thenReturn(new MarketCalendarDay(MarketCountry.KR, tradeDate, false, null, null, null));

        service().collect(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("정규장 마감 10분 전에는 캔들 API를 호출하지 않는다")
    void collect_장마감_전에_수집하지_않는다() {
        Stock stock = mockStock(1L, "005930", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(stock));
        LocalDate tradeDate = NOW.atZone(ZoneOffset.ofHours(9)).toLocalDate();
        when(marketCalendarPort.fetchKrMarketCalendar(tradeDate)).thenReturn(new MarketCalendarDay(
                MarketCountry.KR, tradeDate, true,
                OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), null));

        service().collect(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("빈 캔들 응답은 저장하지 않는다")
    void collect_빈_응답은_저장하지_않는다() {
        Stock stock = mockStock(1L, "EMPTY", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(stock));
        allowCollection(MarketCountry.KR);
        when(marketDataPort.fetchCandles("EMPTY", CandleInterval.ONE_DAY, 1)).thenReturn(List.of());

        service().collect(MarketCountry.KR);

        verify(persistenceService, never()).upsert(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("수집 대상 종목이 없으면 API 를 호출하지 않는다")
    void collect_대상_없으면_API_호출_안한다() {
        when(stockRepository.findRankedByMarketCountry(any(), any()))
                .thenReturn(List.of());

        service().collect(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
    }

    // ── backfill ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("상위 종목은 최근 250봉을 멱등 백필한다")
    void backfill_상위_종목을_백필한다() {
        Stock target = mockStock(1L, "NEW", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(target));
        List<Candle> candles = List.of(candle("KRW"));
        when(marketDataPort.fetchCandles("NEW", CandleInterval.ONE_DAY, 250))
                .thenReturn(candles);

        service().backfill(MarketCountry.KR);

        verify(persistenceService).upsert(1L, MarketCountry.KR, "KRW", candles);
    }

    @Test
    @DisplayName("백필 대상이 하나도 없으면 API 를 호출하지 않는다")
    void backfill_대상_없으면_API_호출_안한다() {
        when(stockRepository.findRankedByMarketCountry(any(), any()))
                .thenReturn(List.of());

        service().backfill(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("백필 중 개별 종목 실패는 나머지 종목에 영향을 주지 않는다")
    void backfill_한_종목_실패해도_나머지는_계속한다() {
        Stock failing = mockStock(1L, "FAIL", "KRW");
        Stock success = mockStock(2L, "OK", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(failing, success));
        when(marketDataPort.fetchCandles("FAIL", CandleInterval.ONE_DAY, 250))
                .thenThrow(new RuntimeException("Toss API 오류"));
        when(marketDataPort.fetchCandles("OK", CandleInterval.ONE_DAY, 250))
                .thenReturn(List.of(candle("KRW")));

        service().backfill(MarketCountry.KR);

        verify(persistenceService, times(1)).upsert(eq(2L), eq(MarketCountry.KR), anyString(), any());
        verify(persistenceService, never()).upsert(eq(1L), any(), anyString(), any());
    }

    @Test
    @DisplayName("백필 빈 응답은 저장하지 않는다")
    void backfill_빈_응답은_저장하지_않는다() {
        Stock stock = mockStock(1L, "EMPTY", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(stock));
        when(marketDataPort.fetchCandles("EMPTY", CandleInterval.ONE_DAY, 250)).thenReturn(List.of());

        service().backfill(MarketCountry.KR);

        verify(persistenceService, never()).upsert(any(), any(), anyString(), any());
    }

    private Stock mockStock(Long id, String symbol, String currency) {
        return mock(Stock.class, invocation -> switch (invocation.getMethod().getName()) {
            case "getStockId" -> id;
            case "getSymbol" -> symbol;
            case "getCurrency" -> currency;
            case "getMarketCountry" -> currency.equalsIgnoreCase("USD") ? MarketCountry.US : MarketCountry.KR;
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private Candle candle(String currency) {
        BigDecimal p = BigDecimal.ONE;
        return new Candle(OffsetDateTime.now(ZoneOffset.UTC), p, p, p, p, p, currency);
    }

    private void allowCollection(MarketCountry marketCountry) {
        LocalDate tradeDate = NOW.atZone(ZoneOffset.ofHours(9)).toLocalDate();
        MarketCalendarDay day = new MarketCalendarDay(
                marketCountry, tradeDate, true,
                OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(600), ZoneOffset.UTC), null);
        when(marketCalendarPort.fetchKrMarketCalendar(tradeDate)).thenReturn(day);
    }
}
