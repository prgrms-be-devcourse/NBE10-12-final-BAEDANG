package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.service.DailyCandleSeedService.SeedResult;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyCandleSeedServiceTest {

    private static final int SEED_COUNT = 200;

    @Mock MarketDataPort marketDataPort;
    @Mock StockRepository stockRepository;
    @Mock DailyCandlePersistenceService persistenceService;
    @Mock DailyCandleRepository dailyCandleRepository;

    /** universeSize 는 넉넉히, TPS 는 페이싱 지연이 없도록 크게 잡아 테스트 속도를 높입니다. */
    private DailyCandleSeedService service() {
        return new DailyCandleSeedService(
                marketDataPort, stockRepository, persistenceService,
                dailyCandleRepository, 100, 1000);
    }

    @Test
    @DisplayName("일봉 이력이 없는 종목만 200일치를 수집·적재한다")
    void seed_이력없는_종목만_수집한다() {
        Stock seeded = mockStock(1L, "005930", "KRW");
        Stock fresh = mockStock(2L, "000660", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(seeded, fresh));
        when(dailyCandleRepository.findStockIdsWithAnyCandle(List.of(1L, 2L)))
                .thenReturn(Set.of(1L));
        List<Candle> candles = List.of(candle("KRW"));
        when(marketDataPort.fetchCandles("000660", CandleInterval.ONE_DAY, SEED_COUNT))
                .thenReturn(candles);

        SeedResult result = service().seed(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles(eq("005930"), any(), anyInt());
        verify(persistenceService).upsert(2L, "KRW", candles);
        verify(persistenceService, never()).upsert(eq(1L), anyString(), any());
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("유니버스가 비어 있으면 캔들 API 를 호출하지 않고 no-op 한다")
    void seed_유니버스_비면_no_op() {
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of());

        SeedResult result = service().seed(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
        verify(dailyCandleRepository, never()).findStockIdsWithAnyCandle(any());
        assertThat(result).isEqualTo(new SeedResult(0, 0, 0, 0));
    }

    @Test
    @DisplayName("개별 종목 수집 실패는 나머지 종목 수집을 막지 않는다")
    void seed_한_종목_실패해도_나머지는_계속한다() {
        Stock failing = mockStock(1L, "FAIL", "KRW");
        Stock success = mockStock(2L, "OK", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(failing, success));
        when(dailyCandleRepository.findStockIdsWithAnyCandle(any())).thenReturn(Set.of());
        when(marketDataPort.fetchCandles("FAIL", CandleInterval.ONE_DAY, SEED_COUNT))
                .thenThrow(new RuntimeException("Toss API 오류"));
        when(marketDataPort.fetchCandles("OK", CandleInterval.ONE_DAY, SEED_COUNT))
                .thenReturn(List.of(candle("KRW")));

        SeedResult result = service().seed(MarketCountry.KR);

        verify(persistenceService, times(1)).upsert(eq(2L), anyString(), any());
        verify(persistenceService, never()).upsert(eq(1L), anyString(), any());
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failure()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 캔들 응답은 저장하지 않고 실패로 집계한다")
    void seed_빈_응답은_저장하지_않는다() {
        Stock stock = mockStock(1L, "EMPTY", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(stock));
        when(dailyCandleRepository.findStockIdsWithAnyCandle(any())).thenReturn(Set.of());
        when(marketDataPort.fetchCandles("EMPTY", CandleInterval.ONE_DAY, SEED_COUNT))
                .thenReturn(List.of());

        SeedResult result = service().seed(MarketCountry.KR);

        verify(persistenceService, never()).upsert(any(), anyString(), any());
        assertThat(result.success()).isZero();
        assertThat(result.failure()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 전량 시드된 시장은 캔들 API 를 호출하지 않는다")
    void seed_전량_시드됨이면_호출_안한다() {
        Stock stock = mockStock(1L, "DONE", "KRW");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(stock));
        when(dailyCandleRepository.findStockIdsWithAnyCandle(List.of(1L)))
                .thenReturn(Set.of(1L));

        SeedResult result = service().seed(MarketCountry.KR);

        verify(marketDataPort, never()).fetchCandles(anyString(), any(), anyInt());
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.success()).isZero();
    }

    @Test
    @DisplayName("seedAll 은 KR·US 를 모두 시드하고 결과를 합산한다")
    void seedAll_모든_시장을_합산한다() {
        Stock kr = mockStock(1L, "005930", "KRW");
        Stock us = mockStock(2L, "NVDA", "USD");
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any()))
                .thenReturn(List.of(kr));
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.US), any()))
                .thenReturn(List.of(us));
        lenient().when(dailyCandleRepository.findStockIdsWithAnyCandle(any())).thenReturn(Set.of());
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, SEED_COUNT))
                .thenReturn(List.of(candle("KRW")));
        when(marketDataPort.fetchCandles("NVDA", CandleInterval.ONE_DAY, SEED_COUNT))
                .thenReturn(List.of(candle("USD")));

        SeedResult result = service().seedAll();

        verify(persistenceService).upsert(1L, "KRW", List.of(candle("KRW")));
        verify(persistenceService).upsert(2L, "USD", List.of(candle("USD")));
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.success()).isEqualTo(2);
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
        return new Candle(
                OffsetDateTime.of(2026, 8, 28, 6, 0, 0, 0, ZoneOffset.UTC),
                p, p, p, p, p, currency);
    }
}
