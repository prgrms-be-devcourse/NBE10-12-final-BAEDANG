package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockInfo;
import com.baedang.stock.port.SymbolInfoPort;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockTradingStatusServiceTest {
    private final SymbolInfoPort port = mock(SymbolInfoPort.class);
    private final StockTradingStatusPersistenceService persistence = mock(StockTradingStatusPersistenceService.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-09-09T01:00:00Z");
    private final StockTradingStatusService service = new StockTradingStatusService(
            port, persistence, stocks, clock, Duration.ofMinutes(5));

    private Stock stock(long id) {
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(id);
        when(stock.getSymbol()).thenReturn("S" + id);
        when(stock.getMarket()).thenReturn("KOSPI");
        when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        when(stock.getCurrency()).thenReturn("KRW");
        when(clock.instant()).thenReturn(now);
        return stock;
    }

    private StockInfo info(String symbol, String status, StockInfo.KrMarketDetail kr) {
        return new StockInfo(symbol, "test", null, null, "KOSPI", "STOCK", true,
                status, "KRW", null, null, null, null, kr);
    }

    @Test
    void 상태를_캐시하되_TTL경계에서는_다시_조회한다() {
        Stock stock = stock(1);
        StockInfo info = info("S1", "ACTIVE", new StockInfo.KrMarketDetail(false, false, false, null));
        when(port.fetchStocks(List.of("S1"))).thenReturn(List.of(info));
        when(stocks.findByStockIdIn(List.of(1L))).thenReturn(List.of(stock));
        assertThat(service.requireCurrent(stock)).isSameAs(stock);
        when(clock.instant()).thenReturn(now.plusSeconds(299));
        service.requireCurrent(stock);
        verify(port).fetchStocks(List.of("S1"));
        when(clock.instant()).thenReturn(now.plusSeconds(300));
        service.requireCurrent(stock);
        verify(port, times(2)).fetchStocks(List.of("S1"));
    }

    @Test
    void 누락과_알수없는상태와_국내상세누락은_거래가능으로_보정하거나_캐시하지_않는다() {
        Stock stock = stock(1);
        when(port.fetchStocks(List.of("S1"))).thenReturn(List.of(),
                List.of(info("S1", "UNKNOWN", new StockInfo.KrMarketDetail(false, false, false, null))),
                List.of(info("S1", "ACTIVE", null)));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.requireCurrent(stock))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_STATUS_UNAVAILABLE));
        }
        verify(port, times(3)).fetchStocks(List.of("S1"));
        verifyNoInteractions(persistence);
    }

    @Test
    void 배치는_한번에_조회하고_누락된_종목만_제외한다() {
        Stock first = stock(1);
        Stock second = stock(2);
        when(port.fetchStocks(List.of("S1", "S2"))).thenReturn(List.of(
                info("S2", "ACTIVE", new StockInfo.KrMarketDetail(false, false, true, null))));
        when(stocks.findByStockIdIn(List.of(2L))).thenReturn(List.of(second));
        assertThat(service.refreshBatch(List.of(first, second))).containsExactly(second);
        verify(persistence, never()).update(eq(1L), any(), any());
        verify(port).fetchStocks(List.of("S1", "S2"));
    }

    @Test
    void 정규화한_동일키_중복응답은_임의선택하거나_캐시하지_않는다() {
        Stock stock = stock(1);
        StockInfo first = info("S1", "ACTIVE", new StockInfo.KrMarketDetail(false, false, false, null));
        StockInfo duplicate = info("s1", "ACTIVE", new StockInfo.KrMarketDetail(false, false, true, null));
        when(port.fetchStocks(List.of("S1"))).thenReturn(List.of(first, duplicate), List.of(first));
        assertThatThrownBy(() -> service.requireCurrent(stock))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_STATUS_UNAVAILABLE));
        verifyNoInteractions(persistence);
        when(stocks.findByStockIdIn(List.of(1L))).thenReturn(List.of(stock));
        assertThat(service.requireCurrent(stock)).isSameAs(stock);
        verify(port, times(2)).fetchStocks(List.of("S1"));
    }

    @Test
    void 같은심볼이라도_시장과_통화가_다른_응답은_일치로_간주하지_않는다() {
        Stock stock = stock(1);
        StockInfo valid = info("s1", "ACTIVE", new StockInfo.KrMarketDetail(false, false, false, null));
        StockInfo otherMarket = new StockInfo("S1", "test", null, null, "NASDAQ", "STOCK", true,
                "ACTIVE", "KRW", null, null, null, null, valid.krMarketDetail());
        StockInfo otherCurrency = new StockInfo("S1", "test", null, null, "KOSPI", "STOCK", true,
                "ACTIVE", "USD", null, null, null, null, valid.krMarketDetail());
        when(port.fetchStocks(List.of("S1"))).thenReturn(Arrays.asList(null, otherMarket, otherCurrency, valid));
        when(stocks.findByStockIdIn(List.of(1L))).thenReturn(List.of(stock));
        assertThat(service.requireCurrent(stock)).isSameAs(stock);
        verify(persistence).update(1L, MarketCountry.KR, valid);
    }

    @Test
    void 동시에_같은_종목을_조회해도_한번만_외부_호출한다() throws Exception {
        Stock stock = stock(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(port.fetchStocks(List.of("S1"))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            return List.of(info("S1", "ACTIVE", new StockInfo.KrMarketDetail(false, false, false, null)));
        });
        when(stocks.findByStockIdIn(List.of(1L))).thenReturn(List.of(stock));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Stock> first = pool.submit(() -> service.requireCurrent(stock));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<Stock> second = pool.submit(() -> service.requireCurrent(stock));
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isSameAs(stock);
            assertThat(second.get(2, TimeUnit.SECONDS)).isSameAs(stock);
        } finally {
            release.countDown();
        }
        verify(port).fetchStocks(List.of("S1"));
    }
}
