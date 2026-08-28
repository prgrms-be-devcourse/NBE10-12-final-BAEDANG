package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.PlaceOrderRequest;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.MarketOrderExecutionContext;
import com.baedang.trading.model.MarketOrderResult;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.entity.OrderSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class MarketOrderServiceTest {

    @Mock MarketOrderPolicy marketOrderPolicy;
    @Mock MarketOrderTransactionService transactionService;
    @Mock StockRepository stockRepository;
    @Mock MarketSessionProvider marketSessionProvider;
    @Mock ExecutionExchangeRateProvider exchangeRateProvider;

    @Test
    void REJECTED가_커밋된_뒤_업무_예외로_변환한다() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                10L, UUID.randomUUID().toString(), "005930", "KR", "BUY", "10");
        MarketOrderCommand command = new MarketOrderCommand(
                request.accountId(),
                UUID.fromString(request.clientOrderId()),
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, new BigDecimal("10")));
        when(marketOrderPolicy.parseCommand(
                request.accountId(), request.clientOrderId(), request.symbol(), request.marketCountry(),
                request.side(), request.quantity()))
                .thenReturn(command);
        when(transactionService.findExisting(1L, command)).thenReturn(Optional.empty());
        Stock stock = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", "KRW", "STOCK");
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        when(marketSessionProvider.currentSession(eq(MarketCountry.KR), any()))
                .thenReturn(new MarketSessionStatus(
                        true, Instant.parse("2026-08-26T02:00:00Z")));
        when(transactionService.execute(eq(1L), eq(command), any(MarketOrderExecutionContext.class)))
                .thenReturn(MarketOrderResult.rejected(ErrorCode.INSUFFICIENT_CASH));

        Instant sessionLookupAt = Instant.parse("2026-08-26T01:00:00Z");
        Instant contextCheckedAt = Instant.parse("2026-08-26T01:00:05Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(sessionLookupAt, contextCheckedAt);
        MarketOrderService service = new MarketOrderService(
                marketOrderPolicy,
                transactionService,
                stockRepository,
                marketSessionProvider,
                exchangeRateProvider,
                new OrderResponseAssembler(),
                clock);

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CASH);
                    assertThat(exception.getData())
                            .containsEntry("retryPolicy", "NEW_CLIENT_ORDER_ID");
                });

        ArgumentCaptor<MarketOrderExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(MarketOrderExecutionContext.class);
        verify(transactionService).execute(eq(1L), eq(command), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).isEqualTo(new MarketOrderExecutionContext(
                MarketCountry.KR,
                true,
                Instant.parse("2026-08-26T02:00:00Z"),
                BigDecimal.ONE,
                contextCheckedAt));
        verify(marketSessionProvider).currentSession(MarketCountry.KR, sessionLookupAt);
        verifyNoInteractions(exchangeRateProvider);
    }

    @Test
    void 멱등_재요청은_외부_시장정보를_조회하지_않는다() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                10L, UUID.randomUUID().toString(), "005930", "KR", "BUY", "10");
        MarketOrderCommand command = new MarketOrderCommand(
                request.accountId(),
                UUID.fromString(request.clientOrderId()),
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, new BigDecimal("10")));
        when(marketOrderPolicy.parseCommand(
                request.accountId(), request.clientOrderId(), request.symbol(), request.marketCountry(),
                request.side(), request.quantity()))
                .thenReturn(command);
        when(transactionService.findExisting(1L, command))
                .thenReturn(Optional.of(MarketOrderResult.rejected(ErrorCode.INSUFFICIENT_CASH)));

        MarketOrderService service = new MarketOrderService(
                marketOrderPolicy,
                transactionService,
                stockRepository,
                marketSessionProvider,
                exchangeRateProvider,
                new OrderResponseAssembler(),
                Clock.fixed(Instant.parse("2026-08-26T01:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CASH);
                    assertThat(exception.getData())
                            .containsEntry("retryPolicy", "NEW_CLIENT_ORDER_ID");
                });

        verifyNoInteractions(stockRepository, marketSessionProvider, exchangeRateProvider);
        verify(transactionService, never()).execute(any(), any(), any());
    }
}
