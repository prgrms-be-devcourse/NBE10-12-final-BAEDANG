package com.baedang.orderbook.scheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.repository.OrderBookVersionRepository;
import com.baedang.orderbook.service.OrderBookGenerator;
import com.baedang.orderbook.service.OrderBookPricePolicy;
import com.baedang.orderbook.service.OrderBookPublicationService;
import com.baedang.orderbook.service.OrderBookRetentionService;
import com.baedang.orderbook.service.TickSizePolicy;
import com.baedang.orderbook.support.MutableClock;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.service.StockTradingStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderBookRefreshSchedulerTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T01:00:00Z"));
    private final MarketSessionProvider sessions = mock(MarketSessionProvider.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final QuoteSnapshotRepository quotes = mock(QuoteSnapshotRepository.class);
    private final StockTradingStatusService statuses = mock(StockTradingStatusService.class);
    private final OrderBookPublicationService publication = mock(OrderBookPublicationService.class);
    private final OrderBookGenerator generator = spy(new OrderBookGenerator(new TickSizePolicy(), new OrderBookPricePolicy(new TickSizePolicy())));
    private final Logger logger = (Logger) LoggerFactory.getLogger(OrderBookRefreshScheduler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private OrderBookRefreshScheduler scheduler;
    private QuoteSnapshot quote;

    @BeforeEach
    void setUp() {
        OrderBookProperties properties = new OrderBookProperties("V2", Duration.ofSeconds(3), Duration.ofSeconds(15),
                new BigDecimal("20000000"), new BigDecimal("15000"), BigDecimal.ONE,
                new BigDecimal("1000000"), 8000, 12000, Duration.ofMinutes(1));
        scheduler = new OrderBookRefreshScheduler(sessions, stocks, quotes, mock(OrderBookVersionRepository.class),
                generator, publication, mock(OrderBookRetentionService.class), properties, clock, statuses);
        when(sessions.currentSession(any(), any())).thenReturn(MarketSessionStatus.closed());
        when(statuses.refreshBatch(any())).thenAnswer(invocation -> invocation.getArgument(0));
        logs.start();
        logger.addAppender(logs);
        target(MarketCountry.KR);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private void target(MarketCountry country) {
        Stock stock = mock(Stock.class);
        when(stock.getStockId()).thenReturn(1L);
        when(stock.getSymbol()).thenReturn("TEST");
        when(stock.getMarketCountry()).thenReturn(country);
        when(stock.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);
        when(stock.getCurrency()).thenReturn(country == MarketCountry.KR ? "KRW" : "USD");
        when(stock.isTradable()).thenReturn(true);
        when(sessions.currentSession(any(), any())).thenReturn(MarketSessionStatus.closed());
        when(sessions.currentSession(eq(country), any())).thenReturn(new MarketSessionStatus(true, clock.instant().plusSeconds(3600)));
        when(stocks.findQuoteTargets(eq(country), anyLong(), any(), any())).thenReturn(List.of(stock));
        quote = new QuoteSnapshot(1L, new BigDecimal("70000"), stock.getCurrency(),
                clock.instant().atOffset(ZoneOffset.UTC), clock.instant().atOffset(ZoneOffset.UTC));
        when(quotes.findByStockIdIn(any())).thenReturn(List.of(quote));
    }

    private void limits(LocalDate date, String lower, String upper) {
        ReflectionTestUtils.setField(quote, "priceLimitDate", date);
        quote.updateLimits(upper == null ? null : new BigDecimal(upper), lower == null ? null : new BigDecimal(lower));
    }

    private void advance(long seconds) {
        clock.advance(Duration.ofSeconds(seconds));
        quote.updatePrice(quote.getLastPrice(), quote.getCurrency(), clock.instant().atOffset(ZoneOffset.UTC), clock.instant().atOffset(ZoneOffset.UTC));
    }

    @Test
    void 미확보는_호가를_종료하고_수집_후_다음_회차에_자동_생성한다() {
        scheduler.refreshOrderBooks();
        verify(publication).closeActive(1L);
        verifyNoInteractions(generator);
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
        limits(LocalDate.of(2026, 9, 15), "49000", "91000");
        scheduler.refreshOrderBooks();
        verify(publication).publish(any(), any());
        assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("미확보 0건"));
    }

    @Test
    void 전일_또는_일부_누락_상하한가는_준비_중으로_처리한다() {
        limits(LocalDate.of(2026, 9, 14), "49000", "91000");
        scheduler.refreshOrderBooks();
        limits(LocalDate.of(2026, 9, 15), null, "91000");
        scheduler.refreshOrderBooks();
        verify(publication, times(2)).closeActive(1L);
        verifyNoInteractions(generator);
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void 미확보_요약은_1분마다_출력하고_조회_실패를_복구로_표시하지_않는다() {
        scheduler.refreshOrderBooks();
        advance(3);
        scheduler.refreshOrderBooks();
        assertThat(logs.list).hasSize(1);
        advance(57);
        scheduler.refreshOrderBooks();
        assertThat(logs.list).hasSize(2);
        when(statuses.refreshBatch(any())).thenThrow(new IllegalStateException("조회 실패"));
        scheduler.refreshOrderBooks();
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("미확보 0건"));
    }

    @Test
    void 미국은_상하한가가_없어도_생성한다() {
        target(MarketCountry.US);
        scheduler.refreshOrderBooks();
        verify(publication).publish(any(), any());
        verify(publication, never()).closeActive(any());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 당일_가격_범위_이상은_WARN을_유지하고_호가를_종료한다() {
        limits(LocalDate.of(2026, 9, 15), "49000", "60000");
        scheduler.refreshOrderBooks();
        verify(publication).closeActive(1L);
        verify(publication, never()).publish(any(), any());
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("호가 생성 거절"));
    }
}
