package com.baedang.stock.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinuteCandleCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T01:00:00Z");

    @Mock MarketSessionProvider marketSessionProvider;
    @Mock StockRepository stockRepository;
    @Mock MarketDataPort marketDataPort;
    @Mock MinuteCandlePersistenceService persistenceService;
    @Mock Stock stockA;
    @Mock Stock stockB;

    private MinuteCandleCollectionService service;

    @BeforeEach
    void setUp() {
        service = new MinuteCandleCollectionService(
                marketSessionProvider,
                stockRepository,
                marketDataPort,
                persistenceService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("정규장이 닫혀 있으면 그 시장은 종목 조회조차 하지 않는다")
    void t1_장_닫힘_스킵() {
        when(marketSessionProvider.isOpen(any(), any())).thenReturn(false);

        service.collectOpenMarkets();

        verify(stockRepository, never()).findRankedByMarketCountry(any(), any());
        verify(marketDataPort, never()).fetchCandles(any(), any(), anyInt());
    }

    @Test
    @DisplayName("상위 종목이 비어 있으면 외부 호출 없이 조용히 넘어간다")
    void t2_상위_종목_없음() {
        when(marketSessionProvider.isOpen(eq(MarketCountry.KR), any())).thenReturn(true);
        when(marketSessionProvider.isOpen(eq(MarketCountry.US), any())).thenReturn(false);
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any(Pageable.class)))
                .thenReturn(List.of());

        service.collectOpenMarkets();

        verify(marketDataPort, never()).fetchCandles(any(), any(), anyInt());
    }

    @Test
    @DisplayName("열린 시장의 상위 종목마다 1분봉을 받아와 저장한다")
    void t3_정상_수집() {
        when(marketSessionProvider.isOpen(eq(MarketCountry.KR), any())).thenReturn(true);
        when(marketSessionProvider.isOpen(eq(MarketCountry.US), any())).thenReturn(false);
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any(Pageable.class)))
                .thenReturn(List.of(stockA, stockB));

        when(stockA.getSymbol()).thenReturn("005930");
        when(stockA.getStockId()).thenReturn(1L);
        when(stockA.getCurrency()).thenReturn("KRW");
        when(stockB.getSymbol()).thenReturn("000660");
        when(stockB.getStockId()).thenReturn(2L);
        when(stockB.getCurrency()).thenReturn("KRW");

        Candle candle = new Candle(
                OffsetDateTime.now(), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, "KRW");
        when(marketDataPort.fetchCandles(eq("005930"), eq(CandleInterval.ONE_MINUTE), anyInt()))
                .thenReturn(List.of(candle));
        when(marketDataPort.fetchCandles(eq("000660"), eq(CandleInterval.ONE_MINUTE), anyInt()))
                .thenReturn(List.of(candle));

        service.collectOpenMarkets();

        verify(persistenceService).upsert(eq(1L), any());
        verify(persistenceService).upsert(eq(2L), any());
        // US는 닫혀 있으니 US 쪽 종목 조회 자체가 없어야 한다.
        verify(stockRepository, never()).findRankedByMarketCountry(eq(MarketCountry.US), any());
    }

    @Test
    @DisplayName("한 종목이 실패해도(통화 불일치) 나머지 종목은 계속 수집한다")
    void t4_부분_실패_계속_진행() {
        when(marketSessionProvider.isOpen(eq(MarketCountry.KR), any())).thenReturn(true);
        when(marketSessionProvider.isOpen(eq(MarketCountry.US), any())).thenReturn(false);
        when(stockRepository.findRankedByMarketCountry(eq(MarketCountry.KR), any(Pageable.class)))
                .thenReturn(List.of(stockA, stockB));

        when(stockA.getSymbol()).thenReturn("005930");
        // stockA.getStockId()는 검증 실패로 절대 호출되지 않는다 — strict stubbing이라 스텁하지 않는다.
        when(stockA.getCurrency()).thenReturn("KRW");
        when(stockB.getSymbol()).thenReturn("000660");
        when(stockB.getStockId()).thenReturn(2L);
        when(stockB.getCurrency()).thenReturn("KRW");

        // stockA는 통화가 안 맞는 캔들을 받는다 — 실패해야 한다.
        Candle mismatched = new Candle(
                OffsetDateTime.now(), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, "USD");
        Candle ok = new Candle(
                OffsetDateTime.now(), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, "KRW");
        when(marketDataPort.fetchCandles(eq("005930"), eq(CandleInterval.ONE_MINUTE), anyInt()))
                .thenReturn(List.of(mismatched));
        when(marketDataPort.fetchCandles(eq("000660"), eq(CandleInterval.ONE_MINUTE), anyInt()))
                .thenReturn(List.of(ok));

        service.collectOpenMarkets();

        // stockA(통화 불일치)는 저장 시도 자체가 없어야 하고, stockB는 정상 저장돼야 한다.
        verify(persistenceService, never()).upsert(eq(1L), any());
        verify(persistenceService, times(1)).upsert(eq(2L), any());
    }
}
