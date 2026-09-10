package com.baedang.trading.service;

import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.service.StockTradingStatusService;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionOutcome;
import com.baedang.trading.repository.TradeOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class LimitOrderExecutionServiceTest {
    private final Instant now = Instant.parse("2026-09-09T01:00:00Z");
    private final TradeOrderRepository orders = mock(TradeOrderRepository.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final StockTradingStatusService statuses = mock(StockTradingStatusService.class);
    private final MarketSessionProvider sessions = mock(MarketSessionProvider.class);
    private final ExecutionExchangeRateProvider rates = mock(ExecutionExchangeRateProvider.class);
    private final LimitExecutionBookReader books = mock(LimitExecutionBookReader.class);
    private final LimitOrderExecutionTransactionService transactions = mock(LimitOrderExecutionTransactionService.class);
    private final OrderPolicy policy = mock(OrderPolicy.class);
    private final TradeOrder order = mock(TradeOrder.class);
    private final Stock stock = mock(Stock.class);
    private final LimitOrderExecutionService service = new LimitOrderExecutionService(orders,stocks,statuses,sessions,rates,books,
            transactions,policy,Clock.fixed(now,ZoneOffset.UTC));

    private com.baedang.trading.model.LimitExecutionPreparation selected() {
        return com.baedang.trading.model.LimitExecutionPreparation.available(2L, OrderSide.BUY,
                new LimitExecutionBook(3L, 0L, now, now, List.of()),
                new com.baedang.trading.model.OrderMarketContext(MarketCountry.KR, true, now.plusSeconds(3600),
                        com.baedang.trading.model.ExecutionRateEvidence.krw(now.atOffset(ZoneOffset.UTC)), now));
    }

    @BeforeEach
    void setup() {
        when(orders.findById(1L)).thenReturn(Optional.of(order));
        when(order.getAccountId()).thenReturn(1L);
        when(order.getStockId()).thenReturn(2L);
        when(order.getOrderType()).thenReturn(OrderType.LIMIT);
        when(order.isActive()).thenReturn(true);
        when(order.getExpiresAt()).thenReturn(now.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        when(order.getSide()).thenReturn(OrderSide.BUY);
        when(stocks.findById(2L)).thenReturn(Optional.of(stock));
        when(stock.getStockId()).thenReturn(2L);
        when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        when(statuses.requireCurrent(stock)).thenReturn(stock);
        when(sessions.currentSession(any(),any())).thenReturn(new MarketSessionStatus(true,now.plusSeconds(3600)));
        when(books.read(stock,OrderSide.BUY,now)).thenReturn(Optional.of(new LimitExecutionBook(3L,0L,now,now,List.of())));
    }

    @Test
    void 호가충돌은_새revision으로_준비해서_딱한번_재시도한다() {
        when(books.read(stock,OrderSide.BUY,now)).thenReturn(
                Optional.of(new LimitExecutionBook(3L,0L,now,now,List.of())),
                Optional.of(new LimitExecutionBook(3L,1L,now,now,List.of())));
        when(transactions.execute(any())).thenReturn(
                LimitExecutionOutcome.deferred(LimitExecutionOutcome.Reason.BOOK_CHANGED),new LimitExecutionOutcome(1,LimitExecutionOutcome.Reason.EXECUTED));
        assertThat(service.execute(1L, selected()).executionCount()).isEqualTo(1);
        ArgumentCaptor<com.baedang.trading.model.LimitExecutionAttempt> attempts = ArgumentCaptor.forClass(com.baedang.trading.model.LimitExecutionAttempt.class);
        verify(transactions,times(2)).execute(attempts.capture());
        assertThat(attempts.getAllValues()).extracting(com.baedang.trading.model.LimitExecutionAttempt::revision).containsExactly(0L,1L);
        verifyNoInteractions(rates);
    }

    @Test
    void 락충돌이_반복되어도_두번까지만_실행한다() {
        when(transactions.execute(any())).thenThrow(new org.springframework.dao.CannotAcquireLockException("busy"));
        assertThat(service.execute(1L, selected()).reason()).isEqualTo(LimitExecutionOutcome.Reason.LOCK_BUSY);
        verify(transactions,times(2)).execute(any());
    }

    @Test
    void 락후_호가충돌의_원인이_새버전이면_재시도_체결없이_재선정한다() {
        when(books.read(stock, OrderSide.BUY, now)).thenReturn(
                Optional.of(new LimitExecutionBook(3L, 0L, now, now, List.of())),
                Optional.of(new LimitExecutionBook(4L, 0L, now, now, List.of())));
        when(transactions.execute(any())).thenReturn(LimitExecutionOutcome.deferred(LimitExecutionOutcome.Reason.BOOK_CHANGED));
        assertThat(service.execute(1L, selected()).reason()).isEqualTo(LimitExecutionOutcome.Reason.PRIORITY_CHANGED);
        verify(transactions, times(1)).execute(any());
    }

    @Test
    void 후보선정후_환율변경은_체결전에_재선정을_요청한다() {
        when(stock.getMarketCountry()).thenReturn(MarketCountry.US);
        when(rates.currentUsdKrwSnapshot()).thenReturn(new com.baedang.market.port.ExecutionExchangeRateSnapshot(
                new java.math.BigDecimal("1400"), now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), now.plusSeconds(60).atOffset(ZoneOffset.UTC)));
        com.baedang.trading.model.LimitExecutionPreparation prepared = service.prepare(2L, OrderSide.BUY);
        when(rates.currentUsdKrwSnapshot()).thenReturn(new com.baedang.market.port.ExecutionExchangeRateSnapshot(
                new java.math.BigDecimal("1300"), now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), now.plusSeconds(60).atOffset(ZoneOffset.UTC)));
        assertThat(service.execute(1L, prepared).reason()).isEqualTo(LimitExecutionOutcome.Reason.PRIORITY_CHANGED);
        verifyNoInteractions(transactions);
    }

    @Test
    void 재시도_사이에_다른체결이_확정됐으면_새체결로_이어가지않는다() {
        when(transactions.execute(any())).thenAnswer(inv -> {
            when(order.getExecutionCount()).thenReturn(1);
            return LimitExecutionOutcome.deferred(LimitExecutionOutcome.Reason.BOOK_CHANGED);
        });
        assertThat(service.execute(1L, selected()).reason()).isEqualTo(LimitExecutionOutcome.Reason.ORDER_CHANGED);
        verify(transactions,times(1)).execute(any());
    }

    @Test
    void 외부준비_지연은_컨텍스트_유효시각을_늘리지않는다() {
        com.baedang.orderbook.support.MutableClock delayedClock = new com.baedang.orderbook.support.MutableClock(now);
        LimitOrderExecutionService delayed = new LimitOrderExecutionService(orders,stocks,statuses,sessions,rates,books,
                transactions,policy,delayedClock);
        when(statuses.requireCurrent(stock)).thenAnswer(invocation -> {
            delayedClock.setCurrent(now.plusSeconds(16));
            return stock;
        });
        when(transactions.execute(any())).thenReturn(LimitExecutionOutcome.deferred(LimitExecutionOutcome.Reason.CONTEXT_EXPIRED));
        delayed.execute(1L, selected());
        ArgumentCaptor<com.baedang.trading.model.LimitExecutionAttempt> attempt = ArgumentCaptor.forClass(com.baedang.trading.model.LimitExecutionAttempt.class);
        verify(transactions).execute(attempt.capture());
        assertThat(attempt.getValue().context().checkedAt()).isEqualTo(now);
        assertThatThrownByContextExpired(attempt.getValue().context(), delayedClock.instant());
    }

    private void assertThatThrownByContextExpired(com.baedang.trading.model.OrderMarketContext context, Instant at) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new OrderPolicy(15,15,new java.math.BigDecimal("1000000")).validateExecutionContextFresh(context,at))
                .isInstanceOfSatisfying(com.baedang.global.error.BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(com.baedang.global.error.ErrorCode.MARKET_CONTEXT_EXPIRED));
    }

    @Test
    void 장외와_호가없음은_상태나_환율을_호출하지않는다() {
        when(sessions.currentSession(any(),any())).thenReturn(MarketSessionStatus.closed());
        assertThat(service.execute(1L, selected()).reason()).isEqualTo(LimitExecutionOutcome.Reason.MARKET_CLOSED);
        verifyNoInteractions(statuses,rates,transactions);
        when(sessions.currentSession(any(),any())).thenReturn(new MarketSessionStatus(true,now.plusSeconds(3600)));
        when(books.read(stock,OrderSide.BUY,now)).thenReturn(Optional.empty());
        assertThat(service.execute(1L, selected()).reason()).isEqualTo(LimitExecutionOutcome.Reason.NO_BOOK);
        verifyNoInteractions(statuses,rates,transactions);
    }

    @Test
    void 상태불명이나_환율누락을_정상값으로_보정하지않는다() {
        when(statuses.requireCurrent(stock)).thenThrow(new com.baedang.global.error.BusinessException(com.baedang.global.error.ErrorCode.STOCK_STATUS_UNAVAILABLE));
        assertThat(service.execute(1L, selected()).reason()).isEqualTo(LimitExecutionOutcome.Reason.STATUS_UNAVAILABLE);
        doReturn(stock).when(statuses).requireCurrent(stock);
        when(stock.getMarketCountry()).thenReturn(MarketCountry.US);
        assertThat(service.execute(1L, selected()).reason()).isEqualTo(LimitExecutionOutcome.Reason.CONTEXT_EXPIRED);
        verifyNoInteractions(transactions);
    }
}
