package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.repository.DailyCandleRepository;
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
import java.util.Set;

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
    @Mock DailyCandleRepository dailyCandleRepository;
    @Mock MarketCalendarPort marketCalendarPort;

    /** universeSize=2 로 고정하여 테스트 속도를 높입니다. */
    private DailyCandleCollectionService service() {
        return new DailyCandleCollectionService(
                marketDataPort, stockRepository, persistenceService,
                dailyCandleRepository, marketCalendarPort,
                Clock.fixed(NOW, ZoneOffset.UTC), 2);
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

        verify(persistenceService).upsert(1L, "KRW", candles);
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

        verify(persistenceService, times(1)).upsert(eq(2L), anyString(), any());
        verify(persistenceService, never()).upsert(eq(1L), anyString(), any());
    }

    @Test
    @DisplayName("휴장일이면 캔들 API를 호출하지 않는다")
    void collect_휴장일이면_수집하지_않는다() {
        LocalDate tradeDate = NOW.atZone(ZoneOffset.ofHours(9)).toLocalDate();
        when(marketCalendarPort.fetchKrMarketCalendar(tradeDate))
                .thenReturn(new MarketCalendarDay(MarketCountry.KR, tradeDate, false, null, null, null));

        service().collect(MarketCountry.KR);

        verify(stockRepository, never()).findRankedByMarketCountry(any(), any());
        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("정규장 마감 10분 전에는 캔들 API를 호출하지 않는다")
    void collect_장마감_전에_수집하지_않는다() {
        LocalDate tradeDate = NOW.atZone(ZoneOffset.ofHours(9)).toLocalDate();
        when(marketCalendarPort.fetchKrMarketCalendar(tradeDate)).thenReturn(new MarketCalendarDay(
                MarketCountry.KR, tradeDate, true,
                OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), null));

        service().collect(MarketCountry.KR);

        verify(stockRepository, never()).findRankedByMarketCountry(any(), any());
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

        verify(persistenceService, never()).upsert(any(), anyString(), any());
    }

    @Test
    @DisplayName("수집 대상 종목이 없으면 API 를 호출하지 않는다")
    void collect_대상_없으면_API_호출_안한다() {
        allowCollection(MarketCountry.KR);
        when(stockRepository.findRankedByMarketCountry(any(), any()))
                .thenReturn(List.of());

        service().collect(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("캘린더 조회 실패는 종목 조회와 캔들 수집을 시작하지 않는다")
    void collect_캘린더_조회_실패시_수집하지_않는다() {
        LocalDate tradeDate = NOW.atZone(ZoneOffset.ofHours(9)).toLocalDate();
        when(marketCalendarPort.fetchKrMarketCalendar(tradeDate))
                .thenThrow(new RuntimeException("502 Bad Gateway"));

        service().collect(MarketCountry.KR);

        verify(stockRepository, never()).findRankedByMarketCountry(any(), any());
        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("아직 당일 확정 일봉이 아니면 저장하거나 성공 처리하지 않는다")
    void collect_이전_거래일_캔들은_저장하지_않는다() {
        Stock stock = mockStock(1L, "STALE", "KRW");
        allowCollection(MarketCountry.KR);
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(stock));
        when(marketDataPort.fetchCandles("STALE", CandleInterval.ONE_DAY, 1))
                .thenReturn(List.of(candleAt(NOW.minusSeconds(86_400), "KRW")));

        service().collect(MarketCountry.KR);

        verify(persistenceService, never()).upsert(any(), anyString(), any());
    }

    @Test
    @DisplayName("재시도에서는 당일 저장이 완료된 종목을 건너뛴다")
    void collect_재시도시_저장된_종목은_건너뛴다() {
        Stock completed = mockStock(1L, "DONE", "KRW");
        Stock missing = mockStock(2L, "MISSING", "KRW");
        allowCollection(MarketCountry.KR);
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(completed, missing));
        LocalDate expectedTradeDate = NOW.atZone(ZoneOffset.ofHours(9)).toLocalDate();
        when(dailyCandleRepository.findStoredStockIds(expectedTradeDate, List.of(1L, 2L)))
                .thenReturn(Set.of(1L));
        List<Candle> candles = List.of(candle("KRW"));
        when(marketDataPort.fetchCandles("MISSING", CandleInterval.ONE_DAY, 1))
                .thenReturn(candles);

        service().collect(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles("DONE", CandleInterval.ONE_DAY, 1);
        verify(persistenceService).upsert(2L, "KRW", candles);
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
        return candleAt(NOW.minusSeconds(1_800), currency);
    }

    private Candle candleAt(Instant instant, String currency) {
        BigDecimal p = BigDecimal.ONE;
        return new Candle(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC), p, p, p, p, p, currency);
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
