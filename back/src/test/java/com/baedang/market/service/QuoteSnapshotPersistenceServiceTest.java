package com.baedang.market.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.repository.QuoteSnapshotBatchRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QuoteSnapshotPersistenceServiceTest {
    private final QuoteSnapshotBatchRepository repository = mock(QuoteSnapshotBatchRepository.class);
    private final MarketTradingDayPolicy policy = mock(MarketTradingDayPolicy.class);
    private final DailyCandleRepository candles = mock(DailyCandleRepository.class);
    private final QuoteSnapshotPersistenceService service = new QuoteSnapshotPersistenceService(repository, policy);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-09T01:00:00Z");

    @Test
    void 정규화하고_동일종목_최신응답을_원본정밀도로_저장한다() {
        Stock stock = stock();
        when(policy.quoteTradeDate(any(), any())).thenReturn(Optional.of(NOW.toLocalDate()));
        when(repository.savePrices(anyList(), any())).thenReturn(1);
        int count = service.saveOrUpdate(List.of(stock), List.of(
                new PriceQuote("aapl", new BigDecimal("100.1234"), NOW, " usd "),
                new PriceQuote("AAPL", new BigDecimal("99"), NOW.minusSeconds(1), "USD")), NOW);
        ArgumentCaptor<List<QuoteSnapshot>> captor = ArgumentCaptor.captor();
        verify(repository).savePrices(captor.capture(), eq(MarketCountry.US));
        assertThat(count).isEqualTo(1);
        assertThat(captor.getValue()).singleElement().satisfies(q -> {
            assertThat(q.getLastPrice()).isEqualByComparingTo("100.1234");
            assertThat(q.getCurrency()).isEqualTo("USD");
            assertThat(q.getQuoteAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 미래_누락_음수_초과정밀도_통화불일치_미요청종목은_저장하지_않는다() {
        Stock stock = stock();
        service.saveOrUpdate(List.of(stock), List.of(
                new PriceQuote("AAPL", BigDecimal.ONE, NOW.plusNanos(1), "USD"),
                new PriceQuote("AAPL", BigDecimal.ONE, null, "USD"),
                new PriceQuote("AAPL", BigDecimal.ZERO, NOW, "USD"),
                new PriceQuote("AAPL", new BigDecimal("1.00001"), NOW, "USD"),
                new PriceQuote("AAPL", BigDecimal.ONE, NOW, "KRW"),
                new PriceQuote("OTHER", BigDecimal.ONE, NOW, "USD")), NOW);
        verifyNoInteractions(repository);
    }

    private Stock stock() {
        Stock stock = mock(Stock.class);
        when(stock.getMarketCountry()).thenReturn(MarketCountry.US);
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("AAPL");
        when(stock.getCurrency()).thenReturn("USD");
        return stock;
    }
}
