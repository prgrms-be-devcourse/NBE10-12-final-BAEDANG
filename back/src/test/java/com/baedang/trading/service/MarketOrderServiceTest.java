package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.MarketOrderRequest;
import com.baedang.trading.dto.MarketOrderResponse;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.MarketOrderReceipt;
import com.baedang.trading.model.MarketOrderResult;
import com.baedang.trading.model.OrderInput;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.OrderTerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketOrderServiceTest {

    @Mock OrderPolicy orderPolicy;
    @Mock MarketOrderTransactionService transactionService;
    @Mock StockRepository stockRepository;
    @Mock MarketSessionProvider marketSessionProvider;
    @Mock ExecutionExchangeRateProvider exchangeRateProvider;

    @Test
    void 미국_시장가는_캐시_원본_수신시각과_유효기간을_트랜잭션에_전달한다() {
        Instant now = Instant.parse("2026-09-04T01:00:00Z");
        var at = now.atOffset(ZoneOffset.UTC);
        var snapshot = new ExecutionExchangeRateSnapshot(new BigDecimal("1383.601234"),
                at.minusSeconds(50), at.minusMinutes(1), at.plusSeconds(5));
        var request = new MarketOrderRequest(10L, UUID.randomUUID().toString(), "AAPL", "US", "BUY", "1");
        var command = new MarketOrderCommand(10L, UUID.fromString(request.clientOrderId()),
                new OrderTerms("AAPL", MarketCountry.US, OrderSide.BUY, BigDecimal.ONE));
        when(orderPolicy.parseInput(10L, request.clientOrderId(), "AAPL", "US", "BUY", "1")).thenReturn(new OrderInput(command.accountId(), command.clientOrderId(), command.terms()));
        when(transactionService.findExisting(1L, command)).thenReturn(Optional.empty());
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("AAPL", MarketCountry.US))
                .thenReturn(Optional.of(Stock.create("AAPL", MarketCountry.US, "NASDAQ", "애플", null, "USD", "STOCK", true)));
        when(marketSessionProvider.currentSession(MarketCountry.US, now)).thenReturn(new MarketSessionStatus(true, Instant.MAX));
        when(exchangeRateProvider.currentUsdKrwSnapshot()).thenReturn(snapshot);
        when(transactionService.execute(eq(1L), eq(command), any())).thenReturn(MarketOrderResult.rejected(ErrorCode.INSUFFICIENT_CASH));
        var service = new MarketOrderService(orderPolicy, transactionService, stockRepository,
                marketSessionProvider, exchangeRateProvider, new MarketOrderResponseAssembler(), Clock.fixed(now, ZoneOffset.UTC), preparedMarketData());

        assertThatThrownBy(() -> service.place(1L, request)).isInstanceOf(BusinessException.class);
        var captor = ArgumentCaptor.forClass(OrderMarketContext.class);
        verify(transactionService).execute(eq(1L), eq(command), captor.capture());
        assertThat(captor.getValue().executionRateEvidence()).isEqualTo(ExecutionRateEvidence.from(snapshot));
        assertThat(captor.getValue().checkedAt()).isEqualTo(now);
        assertThat(captor.getValue().executionRate()).isEqualTo(snapshot.rate());
        verify(exchangeRateProvider, never()).currentUsdKrwRate();
    }

    @Test
    void 거절결과를_업무예외와_새_ID_재시도_정책으로_변환한다() {
        MarketOrderRequest request = new MarketOrderRequest(
                10L, UUID.randomUUID().toString(), "005930", "KR", "BUY", "10");
        MarketOrderCommand command = new MarketOrderCommand(
                request.accountId(),
                UUID.fromString(request.clientOrderId()),
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, new BigDecimal("10")));
        when(orderPolicy.parseInput(
                request.accountId(), request.clientOrderId(), request.symbol(), request.marketCountry(),
                request.side(), request.quantity()))
                .thenReturn(new OrderInput(command.accountId(), command.clientOrderId(), command.terms()));
        when(transactionService.findExisting(1L, command)).thenReturn(Optional.empty());
        Stock stock = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR))
                .thenReturn(Optional.of(stock));
        when(marketSessionProvider.currentSession(eq(MarketCountry.KR), any()))
                .thenReturn(new MarketSessionStatus(
                        true, Instant.parse("2026-08-26T02:00:00Z")));
        when(transactionService.execute(eq(1L), eq(command), any(OrderMarketContext.class)))
                .thenReturn(MarketOrderResult.rejected(ErrorCode.INSUFFICIENT_CASH));

        Instant sessionLookupAt = Instant.parse("2026-08-26T01:00:00Z");
        Instant contextCheckedAt = Instant.parse("2026-08-26T01:00:05Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(sessionLookupAt, contextCheckedAt);
        MarketOrderService service = new MarketOrderService(
                orderPolicy,
                transactionService,
                stockRepository,
                marketSessionProvider,
                exchangeRateProvider,
                new MarketOrderResponseAssembler(),
                clock, preparedMarketData());

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CASH);
                    assertThat(exception.getData())
                            .containsEntry("retryPolicy", "NEW_CLIENT_ORDER_ID");
                });

        ArgumentCaptor<OrderMarketContext> contextCaptor =
                ArgumentCaptor.forClass(OrderMarketContext.class);
        verify(transactionService).execute(eq(1L), eq(command), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).isEqualTo(new OrderMarketContext(
                MarketCountry.KR,
                true,
                Instant.parse("2026-08-26T02:00:00Z"),
                ExecutionRateEvidence.krw(contextCheckedAt.atOffset(ZoneOffset.UTC)),
                contextCheckedAt));
        verify(marketSessionProvider).currentSession(MarketCountry.KR, sessionLookupAt);
        verifyNoInteractions(exchangeRateProvider);
    }

    @Test
    void 멱등_재요청은_외부_시장정보를_조회하지_않는다() {
        MarketOrderRequest request = new MarketOrderRequest(
                10L, UUID.randomUUID().toString(), "005930", "KR", "BUY", "10");
        MarketOrderCommand command = new MarketOrderCommand(
                request.accountId(),
                UUID.fromString(request.clientOrderId()),
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, new BigDecimal("10")));
        when(orderPolicy.parseInput(
                request.accountId(), request.clientOrderId(), request.symbol(), request.marketCountry(),
                request.side(), request.quantity()))
                .thenReturn(new OrderInput(command.accountId(), command.clientOrderId(), command.terms()));
        when(transactionService.findExisting(1L, command))
                .thenReturn(Optional.of(MarketOrderResult.rejected(ErrorCode.INSUFFICIENT_CASH)));

        MarketOrderService service = new MarketOrderService(
                orderPolicy,
                transactionService,
                stockRepository,
                marketSessionProvider,
                exchangeRateProvider,
                new MarketOrderResponseAssembler(),
                Clock.fixed(Instant.parse("2026-08-26T01:00:00Z"), ZoneOffset.UTC), preparedMarketData());

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CASH);
                    assertThat(exception.getData())
                            .containsEntry("retryPolicy", "NEW_CLIENT_ORDER_ID");
                });

        verifyNoInteractions(stockRepository, marketSessionProvider, exchangeRateProvider);
        verify(transactionService, never()).execute(any(), any(), any());
    }

    @Test
    void 요청이_null이면_INVALID_INPUT_예외를_던진다() {
        MarketOrderService service = new MarketOrderService(
                orderPolicy, transactionService, stockRepository,
                marketSessionProvider, exchangeRateProvider, new MarketOrderResponseAssembler(), Clock.systemUTC(), preparedMarketData());

        assertThatThrownBy(() -> service.place(1L, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.getData()).containsEntry("field", "request");
                });
    }

    @Test
    void 종목이_존재하지_않으면_STOCK_NOT_FOUND_예외와_동일_ID_재시도_정책을_반환한다() {
        MarketOrderRequest request = new MarketOrderRequest(10L, UUID.randomUUID().toString(), "UNKNOWN", "KR", "BUY", "1");
        MarketOrderCommand command = new MarketOrderCommand(10L, UUID.fromString(request.clientOrderId()),
                new OrderTerms("UNKNOWN", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE));
        when(orderPolicy.parseInput(10L, request.clientOrderId(), "UNKNOWN", "KR", "BUY", "1")).thenReturn(new OrderInput(command.accountId(), command.clientOrderId(), command.terms()));
        when(transactionService.findExisting(1L, command)).thenReturn(Optional.empty());
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("UNKNOWN", MarketCountry.KR)).thenReturn(Optional.empty());

        MarketOrderService service = new MarketOrderService(
                orderPolicy, transactionService, stockRepository,
                marketSessionProvider, exchangeRateProvider, new MarketOrderResponseAssembler(), Clock.systemUTC(), preparedMarketData());

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_FOUND);
                    assertThat(e.getDetail()).isEqualTo("symbol=UNKNOWN");
                    assertThat(e.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
    }

    @Test
    void 정적_거절_조건에_걸리면_주문을_저장하지_않고_동일_ID_재시도_정책을_반환한다() {
        MarketOrderRequest request = new MarketOrderRequest(10L, UUID.randomUUID().toString(), "005930", "KR", "BUY", "1");
        MarketOrderCommand command = new MarketOrderCommand(10L, UUID.fromString(request.clientOrderId()),
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE));
        when(orderPolicy.parseInput(10L, request.clientOrderId(), "005930", "KR", "BUY", "1")).thenReturn(new OrderInput(command.accountId(), command.clientOrderId(), command.terms()));
        when(transactionService.findExisting(1L, command)).thenReturn(Optional.empty());
        Stock stock = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(stock));
        when(orderPolicy.determineStaticRejection(stock)).thenReturn(ErrorCode.STOCK_SUSPENDED);

        MarketOrderService service = new MarketOrderService(
                orderPolicy, transactionService, stockRepository,
                marketSessionProvider, exchangeRateProvider, new MarketOrderResponseAssembler(), Clock.systemUTC(), preparedMarketData());

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_SUSPENDED);
                    assertThat(e.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
        verifyNoInteractions(marketSessionProvider, exchangeRateProvider);
    }

    @Test
    void 미국_시장가_주문시_환율스냅샷이_null이면_EXCHANGE_RATE_NOT_FOUND를_던진다() {
        Instant now = Instant.parse("2026-09-04T01:00:00Z");
        MarketOrderRequest request = new MarketOrderRequest(10L, UUID.randomUUID().toString(), "AAPL", "US", "BUY", "1");
        MarketOrderCommand command = new MarketOrderCommand(10L, UUID.fromString(request.clientOrderId()),
                new OrderTerms("AAPL", MarketCountry.US, OrderSide.BUY, BigDecimal.ONE));
        when(orderPolicy.parseInput(10L, request.clientOrderId(), "AAPL", "US", "BUY", "1")).thenReturn(new OrderInput(command.accountId(), command.clientOrderId(), command.terms()));
        when(transactionService.findExisting(1L, command)).thenReturn(Optional.empty());
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("AAPL", MarketCountry.US))
                .thenReturn(Optional.of(Stock.create("AAPL", MarketCountry.US, "NASDAQ", "애플", null, "USD", "STOCK", true)));
        when(marketSessionProvider.currentSession(MarketCountry.US, now)).thenReturn(new MarketSessionStatus(true, Instant.MAX));
        when(exchangeRateProvider.currentUsdKrwSnapshot()).thenReturn(null);

        MarketOrderService service = new MarketOrderService(
                orderPolicy, transactionService, stockRepository,
                marketSessionProvider, exchangeRateProvider, new MarketOrderResponseAssembler(), Clock.fixed(now, ZoneOffset.UTC), preparedMarketData());

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
                    assertThat(e.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
    }

    @Test
    void 외부_조회_중_세부메시지_없는_업무예외_발생시_동일_ID_재시도_정책을_부가한다() {
        Instant now = Instant.parse("2026-09-04T01:00:00Z");
        MarketOrderRequest request = new MarketOrderRequest(10L, UUID.randomUUID().toString(), "005930", "KR", "BUY", "1");
        MarketOrderCommand command = new MarketOrderCommand(10L, UUID.fromString(request.clientOrderId()),
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE));
        when(orderPolicy.parseInput(10L, request.clientOrderId(), "005930", "KR", "BUY", "1")).thenReturn(new OrderInput(command.accountId(), command.clientOrderId(), command.terms()));
        when(transactionService.findExisting(1L, command)).thenReturn(Optional.empty());
        Stock stock = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(stock));
        when(marketSessionProvider.currentSession(MarketCountry.KR, now))
                .thenThrow(new BusinessException(ErrorCode.MARKET_CLOSED));

        MarketOrderService service = new MarketOrderService(
                orderPolicy, transactionService, stockRepository,
                marketSessionProvider, exchangeRateProvider, new MarketOrderResponseAssembler(), Clock.fixed(now, ZoneOffset.UTC), preparedMarketData());

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_CLOSED);
                    assertThat(e.getDetail()).isNull();
                    assertThat(e.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
    }

    @Test
    void 외부_조회_중_세부메시지와_기존데이터가_있는_업무예외_발생시_데이터를_보존하고_재시도_정책을_부가한다() {
        Instant now = Instant.parse("2026-09-04T01:00:00Z");
        MarketOrderRequest request = new MarketOrderRequest(10L, UUID.randomUUID().toString(), "005930", "KR", "BUY", "1");
        MarketOrderCommand command = new MarketOrderCommand(10L, UUID.fromString(request.clientOrderId()),
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE));
        when(orderPolicy.parseInput(10L, request.clientOrderId(), "005930", "KR", "BUY", "1")).thenReturn(new OrderInput(command.accountId(), command.clientOrderId(), command.terms()));
        when(transactionService.findExisting(1L, command)).thenReturn(Optional.empty());
        Stock stock = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(stock));
        when(marketSessionProvider.currentSession(MarketCountry.KR, now))
                .thenThrow(new BusinessException(ErrorCode.MARKET_CLOSED, "외부 장애", Map.of("reason", "timeout")));

        MarketOrderService service = new MarketOrderService(
                orderPolicy, transactionService, stockRepository,
                marketSessionProvider, exchangeRateProvider, new MarketOrderResponseAssembler(), Clock.fixed(now, ZoneOffset.UTC), preparedMarketData());

        assertThatThrownBy(() -> service.place(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MARKET_CLOSED);
                    assertThat(e.getDetail()).isEqualTo("외부 장애");
                    assertThat(e.getData())
                            .containsEntry("reason", "timeout")
                            .containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
                });
    }

    @Test
    void 정상_체결_결과는_OrderResponse로_변환하여_반환한다() {
        Instant now = Instant.parse("2026-09-04T01:00:00Z");
        OffsetDateTime orderedAt = now.atOffset(ZoneOffset.UTC);
        MarketOrderRequest request = new MarketOrderRequest(10L, UUID.randomUUID().toString(), "005930", "KR", "BUY", "1");
        MarketOrderCommand command = new MarketOrderCommand(10L, UUID.fromString(request.clientOrderId()),
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE));
        when(orderPolicy.parseInput(10L, request.clientOrderId(), "005930", "KR", "BUY", "1")).thenReturn(new OrderInput(command.accountId(), command.clientOrderId(), command.terms()));
        when(transactionService.findExisting(1L, command)).thenReturn(Optional.empty());
        Stock stock = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(stock));
        when(marketSessionProvider.currentSession(MarketCountry.KR, now)).thenReturn(new MarketSessionStatus(true, Instant.MAX));

        MarketOrderReceipt receipt = new MarketOrderReceipt(
                100L, "FILLED", "005930", MarketCountry.KR, "BUY",
                BigDecimal.ONE, new BigDecimal("70000"), BigDecimal.ONE,
                new BigDecimal("70000"), new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("70010"),
                orderedAt, orderedAt, new BigDecimal("1000000"));
        when(transactionService.execute(eq(1L), eq(command), any())).thenReturn(MarketOrderResult.filled(receipt));

        MarketOrderService service = new MarketOrderService(
                orderPolicy, transactionService, stockRepository,
                marketSessionProvider, exchangeRateProvider, new MarketOrderResponseAssembler(), Clock.fixed(now, ZoneOffset.UTC), preparedMarketData());

        MarketOrderResponse response = service.place(1L, request);
        assertThat(response).isNotNull();
        assertThat(response.orderId()).isEqualTo(100L);
        assertThat(response.symbol()).isEqualTo("005930");
        assertThat(response.status()).isEqualTo("FILLED");
    }

    private OrderMarketDataService preparedMarketData() {
        OrderMarketDataService service = Mockito.mock(OrderMarketDataService.class);
        Mockito.lenient().when(service.refreshStatus(ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.lenient().when(service.prepareEstimate(ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return service;
    }
}
