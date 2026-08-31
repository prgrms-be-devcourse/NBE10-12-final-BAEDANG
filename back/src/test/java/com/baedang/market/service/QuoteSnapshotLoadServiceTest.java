package com.baedang.market.service;

import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuoteSnapshotLoadServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:30:01Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private StockRepository stockRepository;

    @Mock
    private MarketDataPort marketDataPort;

    @Mock
    private QuoteSnapshotPersistenceService persistenceService;

    private QuoteSnapshotLoadService loadService;

    @BeforeEach
    void setUp() {
        loadService = new QuoteSnapshotLoadService(
                stockRepository,
                marketDataPort,
                persistenceService,
                CLOCK,
                100
        );
    }

    @Test
    @DisplayName("유니버스 종목이 없으면 외부 조회와 저장을 건너뛴다")
    void t1() {
        when(stockRepository.findRankedByMarketCountry(
                MarketCountry.KR,
                PageRequest.of(0, 100)
        )).thenReturn(List.of());

        int count = loadService.syncQuotes(MarketCountry.KR);

        assertThat(count).isZero();
        verifyNoInteractions(marketDataPort, persistenceService);
    }

    @Test
    @DisplayName("외부 현재가 응답이 비어 있으면 저장을 호출하지 않는다")
    void t2() {
        Stock stock = stock(1L, "005930", "KRW");
        when(stockRepository.findRankedByMarketCountry(
                MarketCountry.KR,
                PageRequest.of(0, 100)
        )).thenReturn(List.of(stock));
        when(marketDataPort.fetchPrices(List.of("005930"))).thenReturn(List.of());

        int count = loadService.syncQuotes(MarketCountry.KR);

        assertThat(count).isZero();
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("외부 조회 후 UTC 수집 시각과 함께 적재 서비스에 위임한다")
    void t3() {
        Stock stock = stock(1L, "005930", "KRW");
        PriceQuote quote = new PriceQuote(
                "005930",
                new BigDecimal("70000"),
                OffsetDateTime.parse("2026-08-28T09:30:00+09:00"),
                "KRW"
        );
        List<Stock> stocks = List.of(stock);
        List<PriceQuote> quotes = List.of(quote);
        when(stockRepository.findRankedByMarketCountry(
                MarketCountry.KR,
                PageRequest.of(0, 100)
        )).thenReturn(stocks);
        when(marketDataPort.fetchPrices(List.of("005930"))).thenReturn(quotes);
        when(persistenceService.saveOrUpdate(
                stocks,
                quotes,
                NOW.atOffset(ZoneOffset.UTC)
        )).thenReturn(1);

        int count = loadService.syncQuotes(MarketCountry.KR);

        assertThat(count).isEqualTo(1);
        verify(persistenceService).saveOrUpdate(
                stocks,
                quotes,
                NOW.atOffset(ZoneOffset.UTC)
        );

        verify(stockRepository).findRankedByMarketCountry(
                MarketCountry.KR,
                PageRequest.of(0, 100)
        );
    }

    @Test
    @DisplayName("외부 API 오케스트레이터는 DB 트랜잭션을 열지 않는다")
    void t4() throws NoSuchMethodException {
        assertThat(QuoteSnapshotLoadService.class
                .getMethod("syncQuotes", MarketCountry.class)
                .isAnnotationPresent(Transactional.class))
                .isFalse();
    }

    private Stock stock(Long stockId, String symbol, String currency) {
        return mock(Stock.class, invocation -> switch (invocation.getMethod().getName()) {
            case "getStockId" -> stockId;
            case "getSymbol" -> symbol;
            case "getCurrency" -> currency;
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
