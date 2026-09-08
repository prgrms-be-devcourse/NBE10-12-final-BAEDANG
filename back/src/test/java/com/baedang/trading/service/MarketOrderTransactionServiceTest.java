package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.MarketOrderRequest;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.MarketOrderCommand;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.MarketOrderResult;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.LedgerEntryRepository;
import com.baedang.trading.repository.TradeExecutionRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketOrderTransactionServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock StockRepository stockRepository;
    @Mock QuoteSnapshotRepository quoteSnapshotRepository;
    @Mock HoldingRepository holdingRepository;
    @Mock TradeOrderRepository tradeOrderRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock TradeExecutionRepository tradeExecutionRepository;
    @Mock LedgerService ledgerService;
    @Mock MarketOrderSettlementCalculator amountCalculator;
    @Mock OrderPolicy orderPolicy;
    @Mock MarketOrderPolicy marketOrderPolicy;

    private Clock clock;
    private MarketOrderTransactionService service;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;
    private static final Long STOCK_ID = 100L;
    private static final UUID CLIENT_ORDER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T01:00:00Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new MarketOrderTransactionService(
                accountRepository, stockRepository, quoteSnapshotRepository, holdingRepository,
                tradeOrderRepository, ledgerEntryRepository, tradeExecutionRepository, ledgerService,
                amountCalculator, orderPolicy, marketOrderPolicy, clock);
    }

    // ==========================================
    // 1. 트랜잭션 경계 검증 (기존 BoundaryTest 계승)
    // ==========================================

    @Test
    void 시장가_주문_진입점은_외부_트랜잭션_참여를_금지한다() throws Exception {
        var attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                MarketOrderService.class.getMethod("place", Long.class, MarketOrderRequest.class), MarketOrderService.class);

        assertThat(attribute).isNotNull();
        assertThat(attribute.getPropagationBehavior()).isEqualTo(Propagation.NEVER.value());
    }

    @Test
    void DB_변경_서비스의_트랜잭션_전파는_REQUIRED이다() throws Exception {
        var attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                MarketOrderTransactionService.class.getMethod(
                        "execute",
                        Long.class,
                        MarketOrderCommand.class,
                        OrderMarketContext.class), MarketOrderTransactionService.class);

        assertThat(attribute).isNotNull();
        assertThat(attribute.getPropagationBehavior()).isEqualTo(Propagation.REQUIRED.value());
    }

    // ==========================================
    // 2. findExisting 예외 및 멱등 분기 검증
    // ==========================================

    @Test
    void findExisting_계좌가_존재하지_않으면_ACCOUNT_NOT_FOUND_예외를_던진다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findExisting(USER_ID, command))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
                });
    }

    @Test
    void findExisting_기존주문의_종목이_존재하지_않으면_STOCK_NOT_FOUND_예외를_던진다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        Account account = createAccount();
        TradeOrder order = createOrder(OrderStatus.FILLED, null);

        when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(tradeOrderRepository.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(order));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findExisting(USER_ID, command))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_FOUND);
                });
    }

    @Test
    void findExisting_거절된_주문은_거절결과로_정상_재생한다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        Account account = createAccount();
        Stock stock = createStock("005930", MarketCountry.KR);
        TradeOrder rejectedOrder = createOrder(OrderStatus.REJECTED, ErrorCode.INSUFFICIENT_CASH.name());

        when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(tradeOrderRepository.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(rejectedOrder));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock));

        Optional<MarketOrderResult> result = service.findExisting(USER_ID, command);

        assertThat(result).isPresent();
        assertThat(result.get().rejected()).isTrue();
        assertThat(result.get().rejectionReason()).isEqualTo(ErrorCode.INSUFFICIENT_CASH);
    }

    @Test
    void findExisting_거절사유가_유효하지_않거나_null이면_DUPLICATE_ORDER_예외를_던진다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        Account account = createAccount();
        Stock stock = createStock("005930", MarketCountry.KR);
        TradeOrder invalidReasonOrder = createOrder(OrderStatus.REJECTED, "INVALID_REASON_STRING");

        when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(tradeOrderRepository.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(invalidReasonOrder));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> service.findExisting(USER_ID, command))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER);
                });
    }

    @Test
    void findExisting_체결도_거절도_아닌_주문상태면_DUPLICATE_ORDER_예외를_던진다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        Account account = createAccount();
        Stock stock = createStock("005930", MarketCountry.KR);
        TradeOrder pendingOrder = createOrder(OrderStatus.PENDING, null);

        when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(tradeOrderRepository.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(pendingOrder));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> service.findExisting(USER_ID, command))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER);
                });
    }

    @Test
    void findExisting_주문조건이_불일치하면_DUPLICATE_ORDER_예외를_던진다() {
        Account account = createAccount();
        Stock stock = createStock("005930", MarketCountry.KR);
        TradeOrder order = createOrder(OrderStatus.REJECTED, ErrorCode.INSUFFICIENT_CASH.name());

        when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(tradeOrderRepository.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(order));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock));

        // 1. 수량 불일치 (기존 1 != 요청 2)
        MarketOrderCommand qtyMismatch = command("005930", MarketCountry.KR, OrderSide.BUY, new BigDecimal("2"));
        assertThatThrownBy(() -> service.findExisting(USER_ID, qtyMismatch))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER));

        // 2. 방향 불일치 (기존 BUY != 요청 SELL)
        MarketOrderCommand sideMismatch = command("005930", MarketCountry.KR, OrderSide.SELL, BigDecimal.ONE);
        assertThatThrownBy(() -> service.findExisting(USER_ID, sideMismatch))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER));

        // 3. 종목코드 불일치 (기존 005930 != 요청 000660)
        MarketOrderCommand symbolMismatch = command("000660", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        assertThatThrownBy(() -> service.findExisting(USER_ID, symbolMismatch))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER));

        // 4. 시장 국가 불일치 (기존 KR != 요청 US)
        MarketOrderCommand countryMismatch = command("005930", MarketCountry.US, OrderSide.BUY, BigDecimal.ONE);
        assertThatThrownBy(() -> service.findExisting(USER_ID, countryMismatch))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER));

        // 5. 주문 유형 불일치 (기존 MARKET != LIMIT)
        ReflectionTestUtils.setField(order, "orderType", OrderType.LIMIT);
        MarketOrderCommand validCommand = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        assertThatThrownBy(() -> service.findExisting(USER_ID, validCommand))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER));

        // 6. 계좌 ID 불일치 (기존 9999L != 계좌 10L)
        ReflectionTestUtils.setField(order, "orderType", OrderType.MARKET);
        ReflectionTestUtils.setField(order, "accountId", 9999L);
        assertThatThrownBy(() -> service.findExisting(USER_ID, validCommand))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER));

        // 7. 종목 ID 불일치 (기존 9999L != 종목 100L)
        when(stockRepository.findById(9999L)).thenReturn(Optional.of(stock));
        ReflectionTestUtils.setField(order, "accountId", ACCOUNT_ID);
        ReflectionTestUtils.setField(order, "stockId", 9999L);
        assertThatThrownBy(() -> service.findExisting(USER_ID, validCommand))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER));
    }

    // ==========================================
    // 3. execute 예외 및 분기 검증
    // ==========================================

    @Test
    void execute_계좌_비관적락_조회_실패시_ACCOUNT_NOT_FOUND_예외를_던진다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        OrderMarketContext context = executionContext(MarketCountry.KR);
        when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(USER_ID, command, context))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
                });
    }

    @Test
    void execute_종목이_존재하지_않으면_STOCK_NOT_FOUND_예외를_던진다() {
        MarketOrderCommand command = command("UNKNOWN", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        OrderMarketContext context = executionContext(MarketCountry.KR);
        Account account = createAccount();

        when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("UNKNOWN", MarketCountry.KR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(USER_ID, command, context))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_FOUND);
                });
    }

    @Test
    void execute_종목시장과_실행컨텍스트_시장이_불일치하면_STOCK_NOT_FOUND_예외를_던진다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        OrderMarketContext usContext = executionContext(MarketCountry.US);
        Account account = createAccount();
        Stock krStock = createStock("005930", MarketCountry.KR);

        when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(krStock));

        assertThatThrownBy(() -> service.execute(USER_ID, command, usContext))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_FOUND);
                });
    }

    @Test
    void execute_동시성_재시도시_기존_거절주문은_거절결과로_재생한다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        OrderMarketContext context = executionContext(MarketCountry.KR);
        Account account = createAccount();
        Stock stock = createStock("005930", MarketCountry.KR);
        TradeOrder rejectedOrder = createOrder(OrderStatus.REJECTED, ErrorCode.INSUFFICIENT_CASH.name());

        when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock));
        when(tradeOrderRepository.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(rejectedOrder));

        MarketOrderResult result = service.execute(USER_ID, command, context);

        assertThat(result.rejected()).isTrue();
        assertThat(result.rejectionReason()).isEqualTo(ErrorCode.INSUFFICIENT_CASH);
    }

    @Test
    void execute_동시성_재시도시_주문조건이_불일치하면_DUPLICATE_ORDER_예외를_던진다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, new BigDecimal("5"));
        OrderMarketContext context = executionContext(MarketCountry.KR);
        Account account = createAccount();
        Stock stock = createStock("005930", MarketCountry.KR);
        TradeOrder existingOrderWithQty1 = createOrder(OrderStatus.REJECTED, ErrorCode.INSUFFICIENT_CASH.name());

        when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(stockRepository.findById(STOCK_ID)).thenReturn(Optional.of(stock));
        when(tradeOrderRepository.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(existingOrderWithQty1));

        assertThatThrownBy(() -> service.execute(USER_ID, command, context))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER);
                });
    }

    @Test
    void execute_호가스냅샷이_존재하지_않으면_QUOTE_NOT_FOUND_예외를_던진다() {
        MarketOrderCommand command = command("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.ONE);
        OrderMarketContext context = executionContext(MarketCountry.KR);
        Account account = createAccount();
        Stock stock = createStock("005930", MarketCountry.KR);

        when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(stockRepository.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(stock));
        when(tradeOrderRepository.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(quoteSnapshotRepository.findById(STOCK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(USER_ID, command, context))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUOTE_NOT_FOUND);
                });
    }

    // ==========================================
    // 헬퍼 메서드
    // ==========================================

    private MarketOrderCommand command(String symbol, MarketCountry country, OrderSide side, BigDecimal quantity) {
        return new MarketOrderCommand(ACCOUNT_ID, CLIENT_ORDER_ID, new OrderTerms(symbol, country, side, quantity));
    }

    private OrderMarketContext executionContext(MarketCountry country) {
        return new OrderMarketContext(
                country,
                true,
                NOW.plusSeconds(3600),
                ExecutionRateEvidence.krw(AT),
                NOW
        );
    }

    private Account createAccount() {
        Account account = Account.open(USER_ID, 1, new BigDecimal("1000000"), AT);
        ReflectionTestUtils.setField(account, "accountId", ACCOUNT_ID);
        return account;
    }

    private Stock createStock(String symbol, MarketCountry country) {
        Stock stock = Stock.create(symbol, country, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
        ReflectionTestUtils.setField(stock, "stockId", STOCK_ID);
        return stock;
    }

    private TradeOrder createOrder(OrderStatus status, String rejectReason) {
        TradeOrder order = TradeOrder.rejectedMarketOrder(
                ACCOUNT_ID, STOCK_ID, CLIENT_ORDER_ID, OrderSide.BUY,
                BigDecimal.ONE, new BigDecimal("50000"), AT, BigDecimal.ONE,
                rejectReason != null ? rejectReason : "INSUFFICIENT_CASH", AT);
        ReflectionTestUtils.setField(order, "orderId", 50L);
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "rejectReason", rejectReason);
        return order;
    }
}
