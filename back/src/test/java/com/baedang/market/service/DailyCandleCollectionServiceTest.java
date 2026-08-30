package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
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
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyCandleCollectionServiceTest {

    @Mock MarketDataPort marketDataPort;
    @Mock StockRepository stockRepository;
    @Mock DailyCandleRepository dailyCandleRepository;
    @Mock DailyCandlePersistenceService persistenceService;

    /** universeSize=2 로 고정하여 테스트 속도를 높입니다. */
    private DailyCandleCollectionService service() {
        return new DailyCandleCollectionService(
                marketDataPort, stockRepository, dailyCandleRepository, persistenceService, 2);
    }

    // ── collect ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("수집에 성공한 종목은 persistenceService 에 저장한다")
    void collect_성공한_종목을_저장한다() {
        Stock stock = mockStock(1L, "005930", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(stock));
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
        when(marketDataPort.fetchCandles("FAIL", CandleInterval.ONE_DAY, 1))
                .thenThrow(new RuntimeException("Toss API 오류"));
        when(marketDataPort.fetchCandles("OK", CandleInterval.ONE_DAY, 1))
                .thenReturn(List.of(candle("KRW")));

        service().collect(MarketCountry.KR);

        verify(persistenceService, times(1)).upsert(eq(2L), eq(MarketCountry.KR), anyString(), any());
        verify(persistenceService, never()).upsert(eq(1L), any(), anyString(), any());
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
    @DisplayName("일봉이 없는 종목만 백필 대상이다")
    void backfill_일봉없는_종목만_대상이다() {
        Stock target = mockStock(1L, "NEW", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(target));
        when(dailyCandleRepository.existsByStockId(1L)).thenReturn(false);
        List<Candle> candles = List.of(candle("KRW"));
        when(marketDataPort.fetchCandles("NEW", CandleInterval.ONE_DAY, 250))
                .thenReturn(candles);

        service().backfill(MarketCountry.KR);

        verify(persistenceService).upsert(1L, MarketCountry.KR, "KRW", candles);
    }

    @Test
    @DisplayName("이미 일봉이 있는 종목은 백필에서 제외된다")
    void backfill_이미_있는_종목은_건너뛴다() {
        Stock existing = mockStock(1L, "EXIST", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(existing));
        when(dailyCandleRepository.existsByStockId(1L)).thenReturn(true);

        service().backfill(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
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
        when(dailyCandleRepository.existsByStockId(1L)).thenReturn(false);
        when(dailyCandleRepository.existsByStockId(2L)).thenReturn(false);
        when(marketDataPort.fetchCandles("FAIL", CandleInterval.ONE_DAY, 250))
                .thenThrow(new RuntimeException("Toss API 오류"));
        when(marketDataPort.fetchCandles("OK", CandleInterval.ONE_DAY, 250))
                .thenReturn(List.of(candle("KRW")));

        service().backfill(MarketCountry.KR);

        verify(persistenceService, times(1)).upsert(eq(2L), eq(MarketCountry.KR), anyString(), any());
        verify(persistenceService, never()).upsert(eq(1L), any(), anyString(), any());
    }

    private Stock mockStock(Long id, String symbol, String currency) {
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(id);
        when(stock.getSymbol()).thenReturn(symbol);
        when(stock.getCurrency()).thenReturn(currency);
        when(stock.getMarketCountry()).thenReturn(currency.equalsIgnoreCase("USD") ? MarketCountry.US : MarketCountry.KR);
        return stock;
    }

    private Candle candle(String currency) {
        BigDecimal p = BigDecimal.ONE;
        return new Candle(OffsetDateTime.now(ZoneOffset.UTC), p, p, p, p, p, currency);
    }
}
