package com.baedang.market.service;


import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class QuoteSnapshotLoadServiceTest {

    @Mock
    private StockRepository stockRepository;

    @Mock
    private QuoteSnapshotRepository quoteSnapshotRepository;

    @Mock
    private MarketDataPort marketDataPort;

    private QuoteSnapshotLoadService loadService;

    @BeforeEach
    void setUp() {
        loadService = new QuoteSnapshotLoadService(
                stockRepository,
                quoteSnapshotRepository,
                marketDataPort,
                100
        );
    }

    @Test
    @DisplayName("기존 스냅샷이 없으면 신규 QuoteSnapshot을 저장")
    public void t1(){
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("005930");
        when(stock.getCurrency()).thenReturn("KRW");

        when(stockRepository.findRankedByMarketCountry(
                eq(MarketCountry.KR),
                any(Pageable.class)
        )).thenReturn(List.of(stock));

        OffsetDateTime quoteAt = OffsetDateTime.parse("2026-08-28T09:30:00+09:00");
        PriceQuote priceQuote = new PriceQuote("005930", new BigDecimal("70000"), quoteAt, "KRW");
        when(marketDataPort.fetchPrices(List.of("005930"))).thenReturn(List.of(priceQuote));
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L))).thenReturn(List.of());

        int count = loadService.syncQuotes(MarketCountry.KR);

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<QuoteSnapshot> captor = ArgumentCaptor.forClass(QuoteSnapshot.class);
        verify(quoteSnapshotRepository).save(captor.capture());

        QuoteSnapshot saved = captor.getValue();
        assertThat(saved.getStockId()).isEqualTo(1L);
        assertThat(saved.getLastPrice()).isEqualByComparingTo("70000");
        assertThat(saved.getQuoteAt()).isEqualTo(quoteAt);
        assertThat(saved.getCurrency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("기존 스냅샷이 존재 시 가격, 시세 시각 갱신")
    public void t2(){
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("AAPL");
        when(stock.getCurrency()).thenReturn("USD");

        when(stockRepository.findRankedByMarketCountry(
                eq(MarketCountry.US),
                any(Pageable.class)
        )).thenReturn(List.of(stock));

        OffsetDateTime oldQuoteAt = OffsetDateTime.parse("2026-08-27T10:00:00Z");
        QuoteSnapshot existing = new QuoteSnapshot(1L, new BigDecimal("150.00"), "USD", oldQuoteAt);

        OffsetDateTime newQuoteAt = OffsetDateTime.parse("2026-08-28T10:00:00Z");
        PriceQuote newQuote = new PriceQuote("AAPL", new BigDecimal("155.50"), newQuoteAt, "USD");

        when(marketDataPort.fetchPrices(List.of("AAPL"))).thenReturn(List.of(newQuote));
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L))).thenReturn(List.of(existing));

        int count = loadService.syncQuotes(MarketCountry.US);

        assertThat(count).isEqualTo(1);
        assertThat(existing.getLastPrice()).isEqualByComparingTo("155.50");
        assertThat(existing.getQuoteAt()).isEqualTo(newQuoteAt);
        assertThat(existing.getCurrency()).isEqualTo("USD");
        verify(quoteSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("유니버스 종목이 없으면 조회 건너뜀")
    public void t3(){
        when(stockRepository.findRankedByMarketCountry(
                eq(MarketCountry.KR),
                any(Pageable.class)
        )).thenReturn(List.of());

        int count = loadService.syncQuotes(MarketCountry.KR);

        assertThat(count).isZero();
        verifyNoInteractions(marketDataPort, quoteSnapshotRepository);
    }

    @Test
    @DisplayName("체결 시각이 없는 현재가는 저장하지 않는다")
    public void t4() {
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("005930");

        when(stockRepository.findRankedByMarketCountry(
                eq(MarketCountry.KR),
                any(Pageable.class)
        )).thenReturn(List.of(stock));

        PriceQuote quote = new PriceQuote(
                "005930",
                new BigDecimal("70000"),
                null,
                "KRW"
        );

        when(marketDataPort.fetchPrices(List.of("005930")))
                .thenReturn(List.of(quote));
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L)))
                .thenReturn(List.of());

        int count = loadService.syncQuotes(MarketCountry.KR);

        assertThat(count).isZero();
        verify(quoteSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("기존 스냅샷은 응답 통화가 종목 통화와 다르면 갱신하지 않는다")
    public void t5() {
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("005930");
        when(stock.getCurrency()).thenReturn("KRW");

        when(stockRepository.findRankedByMarketCountry(
                eq(MarketCountry.KR),
                any(Pageable.class)
        )).thenReturn(List.of(stock));

        OffsetDateTime oldQuoteAt =
                OffsetDateTime.parse("2026-08-28T09:00:00+09:00");
        QuoteSnapshot existing = new QuoteSnapshot(
                1L,
                new BigDecimal("69000"),
                "KRW",
                oldQuoteAt
        );

        PriceQuote mismatched = new PriceQuote(
                "005930",
                new BigDecimal("150"),
                OffsetDateTime.parse("2026-08-28T09:30:00+09:00"),
                "USD"
        );

        when(marketDataPort.fetchPrices(List.of("005930")))
                .thenReturn(List.of(mismatched));
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L)))
                .thenReturn(List.of(existing));

        int count = loadService.syncQuotes(MarketCountry.KR);

        assertThat(count).isZero();
        assertThat(existing.getLastPrice()).isEqualByComparingTo("69000");
        assertThat(existing.getQuoteAt()).isEqualTo(oldQuoteAt);
        assertThat(existing.getCurrency()).isEqualTo("KRW");
        verify(quoteSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("응답 통화가 없으면 신규 스냅샷을 저장하지 않는다")
    public void t6() {
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("005930");
        when(stock.getCurrency()).thenReturn("KRW");

        when(stockRepository.findRankedByMarketCountry(
                eq(MarketCountry.KR),
                any(Pageable.class)
        )).thenReturn(List.of(stock));

        PriceQuote quote = new PriceQuote(
                "005930",
                new BigDecimal("70000"),
                OffsetDateTime.parse("2026-08-28T09:30:00+09:00"),
                null
        );

        when(marketDataPort.fetchPrices(List.of("005930")))
                .thenReturn(List.of(quote));
        when(quoteSnapshotRepository.findByStockIdIn(List.of(1L)))
                .thenReturn(List.of());

        int count = loadService.syncQuotes(MarketCountry.KR);

        assertThat(count).isZero();
        verify(quoteSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("설정된 유니버스 크기만큼만 랭킹 종목을 조회")
    public void t7() {
        when(stockRepository.findRankedByMarketCountry(
                eq(MarketCountry.KR),
                any(Pageable.class)
        )).thenReturn(List.of());

        loadService.syncQuotes(MarketCountry.KR);

        ArgumentCaptor<Pageable> captor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(stockRepository).findRankedByMarketCountry(
                eq(MarketCountry.KR),
                captor.capture()
        );

        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }
}
