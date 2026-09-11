package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.dto.OrderDetailResponse;
import com.baedang.trading.entity.Holding;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.model.LimitOrderCommand;
import com.baedang.trading.model.MarketOrderAmount;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.repository.HoldingRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.user.entity.Account;
import com.baedang.user.repository.AccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LimitOrderTransactionServiceTest {

    @Mock private AccountRepository accounts;
    @Mock private TradeOrderRepository orders;
    @Mock private HoldingRepository holdings;
    @Mock private StockRepository stocks;
    @Mock private QuoteSnapshotRepository quotes;
    @Mock private OrderPolicy policy;
    @Mock private EntityManager entityManager;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;
    private static final Long STOCK_ID = 100L;
    private static final UUID CLIENT_ORDER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-08T01:00:00Z");
    private static final OffsetDateTime AT = NOW.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);

    private static final MarketOrderAmount DUMMY_AMOUNT = new MarketOrderAmount(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private LimitOrderTransactionService service;
    private Stock krStock;
    private Account activeAccount;

    @BeforeEach
    void setUp() {
        service = new LimitOrderTransactionService(accounts, orders, holdings, stocks, quotes, policy, clock,
                mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        krStock = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
        ReflectionTestUtils.setField(krStock, "stockId", STOCK_ID);

        activeAccount = Account.open(USER_ID, 1, new BigDecimal("50000000"), AT.minusHours(1));
        ReflectionTestUtils.setField(activeAccount, "accountId", ACCOUNT_ID);
    }

    private LimitOrderCommand buyCommand(BigDecimal qty, BigDecimal price) {
        return new LimitOrderCommand(
                ACCOUNT_ID, CLIENT_ORDER_ID,
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, qty),
                price, "KRW");
    }

    private LimitOrderCommand sellCommand(BigDecimal qty, BigDecimal price) {
        return new LimitOrderCommand(
                ACCOUNT_ID, CLIENT_ORDER_ID,
                new OrderTerms("005930", MarketCountry.KR, OrderSide.SELL, qty),
                price, "KRW");
    }

    private OrderMarketContext marketContext(boolean isOpen) {
        OrderMarketContext ctx = mock(OrderMarketContext.class);
        lenient().when(ctx.isMarketOpenAt(any())).thenReturn(isOpen);
        lenient().when(ctx.executionRate()).thenReturn(BigDecimal.ONE);
        lenient().when(ctx.marketOpenUntil()).thenReturn(NOW.plusSeconds(3600));
        return ctx;
    }

    private TradeOrder createPendingOrder(OrderSide side, BigDecimal qty, BigDecimal price, BigDecimal reserve) {
        TradeOrder order = TradeOrder.pendingLimitOrder(
                ACCOUNT_ID, STOCK_ID, CLIENT_ORDER_ID, side, qty,
                price, reserve, AT.minusMinutes(10), AT.plusHours(1),
                price, "KRW", BigDecimal.ONE);
        ReflectionTestUtils.setField(order, "orderId", 1L);
        return order;
    }

    // ==========================================
    // 1. existing() 분기 테스트
    // ==========================================
    @Nested
    @DisplayName("existing() 멱등 조회 검증")
    class ExistingTests {

        @Test
        void 계좌가_없으면_ACCOUNT_NOT_FOUND() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            when(accounts.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.existing(USER_ID, command))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        }

        @Test
        void 기존_주문이_존재하고_조건이_일치하면_재생_성공() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            TradeOrder existingOrder = createPendingOrder(OrderSide.BUY, BigDecimal.TEN, new BigDecimal("50000"), new BigDecimal("500000"));

            when(accounts.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(existingOrder));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.of(krStock));

            Optional<OrderDetailResponse> result = service.existing(USER_ID, command);
            assertThat(result).isPresent();
            assertThat(result.get().orderId()).isEqualTo(1L);
        }

        @ParameterizedTest(name = "기존 주문과 {0}이면 DUPLICATE_ORDER")
        @MethodSource("com.baedang.trading.service.LimitOrderTransactionServiceTest#mismatchedOrders")
        void 기존_주문과_조건이_불일치하면_DUPLICATE_ORDER(String description, LimitOrderCommand command, TradeOrder order, Stock stock) {
            when(accounts.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(order));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.of(stock));

            assertThatThrownBy(() -> service.existing(USER_ID, command))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER));
        }

        @Test
        void 기존_주문의_종목이_존재하지_않으면_STOCK_NOT_FOUND() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            TradeOrder existingOrder = createPendingOrder(OrderSide.BUY, BigDecimal.TEN, new BigDecimal("50000"), new BigDecimal("500000"));

            when(accounts.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(existingOrder));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.existing(USER_ID, command))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_FOUND));
        }

        @Test
        void 기존_주문이_없고_계좌가_활성이면_empty_반환() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            when(accounts.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());

            Optional<OrderDetailResponse> result = service.existing(USER_ID, command);
            assertThat(result).isEmpty();
        }

        @Test
        void 기존_주문이_없는데_계좌가_비활성이면_ACCOUNT_ROUND_CHANGED() {
            activeAccount.close(AT);
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            when(accounts.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.existing(USER_ID, command))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_ROUND_CHANGED));
        }
    }

    static Stream<Arguments> mismatchedOrders() {
        Stock normalStock = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
        ReflectionTestUtils.setField(normalStock, "stockId", STOCK_ID);

        Stock usStock = Stock.create("AAPL", MarketCountry.US, "NASDAQ", "Apple", null, "USD", "STOCK", true);
        ReflectionTestUtils.setField(usStock, "stockId", STOCK_ID);

        Stock diffSymbolStock = Stock.create("005935", MarketCountry.KR, "KOSPI", "삼성전자우", null, "KRW", "STOCK", false);
        ReflectionTestUtils.setField(diffSymbolStock, "stockId", STOCK_ID);

        TradeOrder normalOrder = TradeOrder.pendingLimitOrder(
                ACCOUNT_ID, STOCK_ID, CLIENT_ORDER_ID, OrderSide.BUY, BigDecimal.TEN,
                new BigDecimal("50000"), new BigDecimal("500000"),
                AT.minusMinutes(10), AT.plusHours(1),
                new BigDecimal("50000"), "KRW", BigDecimal.ONE);
        ReflectionTestUtils.setField(normalOrder, "orderId", 1L);

        TradeOrder sellOrder = TradeOrder.pendingLimitOrder(
                ACCOUNT_ID, STOCK_ID, CLIENT_ORDER_ID, OrderSide.SELL, BigDecimal.TEN,
                new BigDecimal("50000"), BigDecimal.ZERO,
                AT.minusMinutes(10), AT.plusHours(1),
                new BigDecimal("50000"), "KRW", BigDecimal.ONE);
        ReflectionTestUtils.setField(sellOrder, "orderId", 1L);

        TradeOrder marketOrder = mock(TradeOrder.class);
        when(marketOrder.getOrderType()).thenReturn(OrderType.MARKET);
        when(marketOrder.getStockId()).thenReturn(STOCK_ID);

        TradeOrder usdOrder = TradeOrder.pendingLimitOrder(
                ACCOUNT_ID, STOCK_ID, CLIENT_ORDER_ID, OrderSide.BUY, BigDecimal.TEN,
                new BigDecimal("50000"), new BigDecimal("500000"),
                AT.minusMinutes(10), AT.plusHours(1),
                new BigDecimal("50"), "USD", new BigDecimal("1300"));
        ReflectionTestUtils.setField(usdOrder, "orderId", 1L);

        LimitOrderCommand standardCommand = new LimitOrderCommand(
                ACCOUNT_ID, CLIENT_ORDER_ID,
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.TEN),
                new BigDecimal("50000"), "KRW");

        LimitOrderCommand diffQtyCommand = new LimitOrderCommand(
                ACCOUNT_ID, CLIENT_ORDER_ID,
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, new BigDecimal("20")),
                new BigDecimal("50000"), "KRW");

        LimitOrderCommand diffPriceCommand = new LimitOrderCommand(
                ACCOUNT_ID, CLIENT_ORDER_ID,
                new OrderTerms("005930", MarketCountry.KR, OrderSide.BUY, BigDecimal.TEN),
                new BigDecimal("60000"), "KRW");

        return Stream.of(
                Arguments.of("수량 불일치", diffQtyCommand, normalOrder, normalStock),
                Arguments.of("주문유형 불일치", standardCommand, marketOrder, normalStock),
                Arguments.of("방향 불일치", standardCommand, sellOrder, normalStock),
                Arguments.of("국가 불일치", standardCommand, normalOrder, usStock),
                Arguments.of("심볼 불일치", standardCommand, normalOrder, diffSymbolStock),
                Arguments.of("통화 불일치", standardCommand, usdOrder, normalStock),
                Arguments.of("가격 불일치", diffPriceCommand, normalOrder, normalStock)
        );
    }

    // ==========================================
    // 2. accept() 분기 테스트
    // ==========================================
    @Nested
    @DisplayName("accept() 주문 접수 검증")
    class AcceptTests {

        @Test
        void 계좌가_없으면_ACCOUNT_NOT_FOUND() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.accept(USER_ID, command, marketContext(true), mock(LimitOrderPricing.Price.class)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        }

        @Test
        void 기존_주문이_이미_있으면_replay_반환() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            TradeOrder existingOrder = createPendingOrder(OrderSide.BUY, BigDecimal.TEN, new BigDecimal("50000"), new BigDecimal("500000"));

            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.of(existingOrder));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.of(krStock));

            OrderDetailResponse response = service.accept(USER_ID, command, marketContext(true), mock(LimitOrderPricing.Price.class));
            assertThat(response.orderId()).isEqualTo(1L);
        }

        @Test
        void 계좌가_닫혀있으면_ACCOUNT_ROUND_CHANGED() {
            activeAccount.close(AT);
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.accept(USER_ID, command, marketContext(true), mock(LimitOrderPricing.Price.class)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_ROUND_CHANGED));
        }

        @Test
        void 종목이_없으면_STOCK_NOT_FOUND() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
            when(stocks.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.accept(USER_ID, command, marketContext(true), mock(LimitOrderPricing.Price.class)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_FOUND));
        }

        @Test
        void 시세가_없으면_QUOTE_NOT_FOUND() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
            when(stocks.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(krStock));
            when(quotes.findById(STOCK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.accept(USER_ID, command, marketContext(true), mock(LimitOrderPricing.Price.class)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUOTE_NOT_FOUND));
        }

        @Test
        void 통화불일치시_QUOTE_CURRENCY_MISMATCH() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            QuoteSnapshot quote = mock(QuoteSnapshot.class);
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
            when(stocks.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(krStock));
            when(quotes.findById(STOCK_ID)).thenReturn(Optional.of(quote));
            when(policy.hasValidCurrencyForMarket(krStock, quote)).thenReturn(false);

            assertThatThrownBy(() -> service.accept(USER_ID, command, marketContext(true), mock(LimitOrderPricing.Price.class)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUOTE_CURRENCY_MISMATCH));
        }

        @ParameterizedTest(name = "{0} 거절 시 REJECTED 주문 생성")
        @CsvSource({
                "STOCK_SUSPENDED, true",
                "MARKET_CLOSED, false",
                "STALE_QUOTE, true"
        })
        void 시장_및_시세_불능시_REJECTED_주문_생성(ErrorCode reason, boolean marketOpen) {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            QuoteSnapshot quote = mock(QuoteSnapshot.class);
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
            when(stocks.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(krStock));
            when(quotes.findById(STOCK_ID)).thenReturn(Optional.of(quote));
            when(policy.hasValidCurrencyForMarket(krStock, quote)).thenReturn(true);
            if (reason == ErrorCode.STOCK_SUSPENDED) {
                when(policy.determineStaticRejection(krStock)).thenReturn(ErrorCode.STOCK_SUSPENDED);
            } else if (reason == ErrorCode.STALE_QUOTE) {
                when(policy.validateQuoteTime(quote, NOW)).thenReturn(ErrorCode.STALE_QUOTE);
            }
            when(orders.save(any(TradeOrder.class))).thenAnswer(i -> i.getArgument(0));

            LimitOrderPricing.Price price = new LimitOrderPricing.Price(
                    new BigDecimal("50000"), new BigDecimal("500000"), DUMMY_AMOUNT);

            OrderDetailResponse response = service.accept(USER_ID, command, marketContext(marketOpen), price);
            assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(response.rejectReason()).isEqualTo(reason.name());
        }

        @Test
        void 매수시_예수금_부족하면_INSUFFICIENT_CASH_거절() {
            LimitOrderCommand command = buyCommand(new BigDecimal("10000"), new BigDecimal("50000"));
            QuoteSnapshot quote = mock(QuoteSnapshot.class);
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
            when(stocks.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(krStock));
            when(quotes.findById(STOCK_ID)).thenReturn(Optional.of(quote));
            when(policy.hasValidCurrencyForMarket(krStock, quote)).thenReturn(true);
            when(orders.save(any(TradeOrder.class))).thenAnswer(i -> i.getArgument(0));

            LimitOrderPricing.Price price = new LimitOrderPricing.Price(
                    new BigDecimal("50000"), new BigDecimal("100000000"), DUMMY_AMOUNT);

            OrderDetailResponse response = service.accept(USER_ID, command, marketContext(true), price);
            assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(response.rejectReason()).isEqualTo(ErrorCode.INSUFFICIENT_CASH.name());
        }

        @Test
        void 매도시_보유주식이_없으면_INSUFFICIENT_QUANTITY_거절() {
            LimitOrderCommand command = sellCommand(BigDecimal.TEN, new BigDecimal("50000"));
            QuoteSnapshot quote = mock(QuoteSnapshot.class);
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
            when(stocks.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(krStock));
            when(quotes.findById(STOCK_ID)).thenReturn(Optional.of(quote));
            when(policy.hasValidCurrencyForMarket(krStock, quote)).thenReturn(true);
            when(holdings.findByAccountIdAndStockIdForUpdate(ACCOUNT_ID, STOCK_ID)).thenReturn(Optional.empty());
            when(orders.save(any(TradeOrder.class))).thenAnswer(i -> i.getArgument(0));

            LimitOrderPricing.Price price = new LimitOrderPricing.Price(
                    new BigDecimal("50000"), BigDecimal.ZERO, DUMMY_AMOUNT);

            OrderDetailResponse response = service.accept(USER_ID, command, marketContext(true), price);
            assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(response.rejectReason()).isEqualTo(ErrorCode.INSUFFICIENT_QUANTITY.name());
        }

        @Test
        void 매도시_보유수량이_부족하면_INSUFFICIENT_QUANTITY_거절() {
            LimitOrderCommand command = sellCommand(new BigDecimal("20"), new BigDecimal("50000"));
            Holding holding = Holding.firstBuy(ACCOUNT_ID, STOCK_ID, BigDecimal.TEN, BigDecimal.ZERO, new BigDecimal("500000"), AT.minusHours(1));

            QuoteSnapshot quote = mock(QuoteSnapshot.class);
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
            when(stocks.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(krStock));
            when(quotes.findById(STOCK_ID)).thenReturn(Optional.of(quote));
            when(policy.hasValidCurrencyForMarket(krStock, quote)).thenReturn(true);
            when(holdings.findByAccountIdAndStockIdForUpdate(ACCOUNT_ID, STOCK_ID)).thenReturn(Optional.of(holding));
            when(orders.save(any(TradeOrder.class))).thenAnswer(i -> i.getArgument(0));

            LimitOrderPricing.Price price = new LimitOrderPricing.Price(
                    new BigDecimal("50000"), BigDecimal.ZERO, DUMMY_AMOUNT);

            OrderDetailResponse response = service.accept(USER_ID, command, marketContext(true), price);
            assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
            assertThat(response.rejectReason()).isEqualTo(ErrorCode.INSUFFICIENT_QUANTITY.name());
        }

        @Test
        void 매수_정상_접수_성공시_예수금_동결_및_PENDING_저장() {
            LimitOrderCommand command = buyCommand(BigDecimal.TEN, new BigDecimal("50000"));
            QuoteSnapshot quote = mock(QuoteSnapshot.class);
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
            when(stocks.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(krStock));
            when(quotes.findById(STOCK_ID)).thenReturn(Optional.of(quote));
            when(policy.hasValidCurrencyForMarket(krStock, quote)).thenReturn(true);
            when(orders.save(any(TradeOrder.class))).thenAnswer(i -> i.getArgument(0));

            BigDecimal reserve = new BigDecimal("500000");
            LimitOrderPricing.Price price = new LimitOrderPricing.Price(
                    new BigDecimal("50000"), reserve, DUMMY_AMOUNT);

            BigDecimal initialLockedCash = activeAccount.getLockedCash();
            OrderDetailResponse response = service.accept(USER_ID, command, marketContext(true), price);

            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(activeAccount.getLockedCash()).isEqualByComparingTo(initialLockedCash.add(reserve));
        }

        @Test
        void 매도_정상_접수_성공시_보유수량_동결_및_PENDING_저장() {
            LimitOrderCommand command = sellCommand(BigDecimal.TEN, new BigDecimal("50000"));
            Holding holding = Holding.firstBuy(ACCOUNT_ID, STOCK_ID, new BigDecimal("20"), BigDecimal.ZERO, new BigDecimal("1000000"), AT.minusHours(1));

            QuoteSnapshot quote = mock(QuoteSnapshot.class);
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findByAccountIdAndClientOrderId(ACCOUNT_ID, CLIENT_ORDER_ID)).thenReturn(Optional.empty());
            when(stocks.findBySymbolIgnoreCaseAndMarketCountry("005930", MarketCountry.KR)).thenReturn(Optional.of(krStock));
            when(quotes.findById(STOCK_ID)).thenReturn(Optional.of(quote));
            when(policy.hasValidCurrencyForMarket(krStock, quote)).thenReturn(true);
            when(holdings.findByAccountIdAndStockIdForUpdate(ACCOUNT_ID, STOCK_ID)).thenReturn(Optional.of(holding));
            when(orders.save(any(TradeOrder.class))).thenAnswer(i -> i.getArgument(0));

            LimitOrderPricing.Price price = new LimitOrderPricing.Price(
                    new BigDecimal("50000"), BigDecimal.ZERO, DUMMY_AMOUNT);

            BigDecimal initialLockedQty = holding.getLockedQuantity();
            OrderDetailResponse response = service.accept(USER_ID, command, marketContext(true), price);

            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(holding.getLockedQuantity()).isEqualByComparingTo(initialLockedQty.add(BigDecimal.TEN));
        }
    }

    // ==========================================
    // 3. close() 분기 테스트
    // ==========================================
    @Nested
    @DisplayName("close() 주문 취소 및 만료 검증")
    class CloseTests {

        @Test
        void expiration_플래그가_true면_lock_timeout_쿼리를_실행한다() {
            Query query = mock(Query.class);
            when(entityManager.createNativeQuery(anyString())).thenReturn(query);
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.close(USER_ID, ACCOUNT_ID, 1L, true))
                    .isInstanceOf(BusinessException.class);

            verify(entityManager).createNativeQuery("SET LOCAL lock_timeout = '2s'");
            verify(query).executeUpdate();
        }

        @Test
        void 계좌가_없으면_ORDER_NOT_FOUND() {
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.close(USER_ID, ACCOUNT_ID, 1L, false))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        void 주문이_없거나_계좌가_일치하지_않으면_ORDER_NOT_FOUND() {
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findForUpdate(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.close(USER_ID, ACCOUNT_ID, 1L, false))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));

            TradeOrder otherOrder = createPendingOrder(OrderSide.BUY, BigDecimal.TEN, new BigDecimal("50000"), new BigDecimal("500000"));
            ReflectionTestUtils.setField(otherOrder, "accountId", 999L);
            when(orders.findForUpdate(2L)).thenReturn(Optional.of(otherOrder));

            assertThatThrownBy(() -> service.close(USER_ID, ACCOUNT_ID, 2L, false))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        void 비활성_주문이면_종료하지_않고_현상태_반환() {
            TradeOrder filledOrder = mock(TradeOrder.class);
            when(filledOrder.getAccountId()).thenReturn(ACCOUNT_ID);
            when(filledOrder.getStockId()).thenReturn(STOCK_ID);
            when(filledOrder.getOrderType()).thenReturn(OrderType.LIMIT);
            when(filledOrder.isActive()).thenReturn(false);

            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findForUpdate(1L)).thenReturn(Optional.of(filledOrder));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.of(krStock));

            OrderDetailResponse response = service.close(USER_ID, ACCOUNT_ID, 1L, false);
            assertThat(response).isNotNull();
            verify(filledOrder, never()).cancel(any());
            verify(filledOrder, never()).expire(any());
        }

        @Test
        void 주문유형이_LIMIT가_아니면_종료하지_않고_현상태_반환() {
            TradeOrder marketOrder = mock(TradeOrder.class);
            when(marketOrder.getAccountId()).thenReturn(ACCOUNT_ID);
            when(marketOrder.getStockId()).thenReturn(STOCK_ID);
            when(marketOrder.getOrderType()).thenReturn(OrderType.MARKET);

            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findForUpdate(1L)).thenReturn(Optional.of(marketOrder));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.of(krStock));

            OrderDetailResponse response = service.close(USER_ID, ACCOUNT_ID, 1L, false);
            assertThat(response).isNotNull();
            verify(marketOrder, never()).cancel(any());
            verify(marketOrder, never()).expire(any());
        }

        @Test
        void 만료_요청인데_만료기한에_도달하지_않았으면_조기_반환() {
            TradeOrder pendingOrder = createPendingOrder(OrderSide.BUY, BigDecimal.TEN, new BigDecimal("50000"), new BigDecimal("500000"));

            Query query = mock(Query.class);
            when(entityManager.createNativeQuery(anyString())).thenReturn(query);
            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findForUpdate(1L)).thenReturn(Optional.of(pendingOrder));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.of(krStock));

            OrderDetailResponse response = service.close(USER_ID, ACCOUNT_ID, 1L, true);
            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(pendingOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        @Test
        void 매도주문_취소시_보유종목이_없으면_INTERNAL_ERROR() {
            TradeOrder pendingSellOrder = createPendingOrder(OrderSide.SELL, BigDecimal.TEN, new BigDecimal("50000"), BigDecimal.ZERO);

            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findForUpdate(1L)).thenReturn(Optional.of(pendingSellOrder));
            when(holdings.findByAccountIdAndStockIdForUpdate(ACCOUNT_ID, STOCK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.close(USER_ID, ACCOUNT_ID, 1L, false))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }

        @Test
        void 매수주문_정상_취소시_동결예수금_해제() {
            BigDecimal reserve = new BigDecimal("500000");
            activeAccount.reserveCash(reserve);
            TradeOrder pendingOrder = createPendingOrder(OrderSide.BUY, BigDecimal.TEN, new BigDecimal("50000"), reserve);

            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findForUpdate(1L)).thenReturn(Optional.of(pendingOrder));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.of(krStock));

            OrderDetailResponse response = service.close(USER_ID, ACCOUNT_ID, 1L, false);

            assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(activeAccount.getLockedCash()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void 매도주문_정상_취소시_동결수량_해제() {
            Holding holding = Holding.firstBuy(ACCOUNT_ID, STOCK_ID, new BigDecimal("20"), BigDecimal.ZERO, new BigDecimal("1000000"), AT.minusHours(1));
            holding.reserveQuantity(BigDecimal.TEN, AT.minusMinutes(5));
            TradeOrder pendingSellOrder = createPendingOrder(OrderSide.SELL, BigDecimal.TEN, new BigDecimal("50000"), BigDecimal.ZERO);

            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findForUpdate(1L)).thenReturn(Optional.of(pendingSellOrder));
            when(holdings.findByAccountIdAndStockIdForUpdate(ACCOUNT_ID, STOCK_ID)).thenReturn(Optional.of(holding));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.of(krStock));

            OrderDetailResponse response = service.close(USER_ID, ACCOUNT_ID, 1L, false);

            assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(holding.getLockedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void 만료_도래한_매수주문_만료처리시_EXPIRED상태와_예수금해제() {
            Query query = mock(Query.class);
            when(entityManager.createNativeQuery(anyString())).thenReturn(query);

            BigDecimal reserve = new BigDecimal("500000");
            activeAccount.reserveCash(reserve);
            TradeOrder expiredDueOrder = TradeOrder.pendingLimitOrder(
                    ACCOUNT_ID, STOCK_ID, CLIENT_ORDER_ID, OrderSide.BUY, BigDecimal.TEN,
                    new BigDecimal("50000"), reserve, AT.minusMinutes(10), AT.minusMinutes(1),
                    new BigDecimal("50000"), "KRW", BigDecimal.ONE);
            ReflectionTestUtils.setField(expiredDueOrder, "orderId", 1L);

            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findForUpdate(1L)).thenReturn(Optional.of(expiredDueOrder));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.of(krStock));

            OrderDetailResponse response = service.close(USER_ID, ACCOUNT_ID, 1L, true);

            assertThat(response.status()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(activeAccount.getLockedCash()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void 종목정보_조회실패시_INTERNAL_ERROR() {
            BigDecimal reserve = new BigDecimal("500000");
            activeAccount.reserveCash(reserve);
            TradeOrder pendingOrder = createPendingOrder(OrderSide.BUY, BigDecimal.TEN, new BigDecimal("50000"), reserve);

            when(accounts.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(activeAccount));
            when(orders.findForUpdate(1L)).thenReturn(Optional.of(pendingOrder));
            when(stocks.findById(STOCK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.close(USER_ID, ACCOUNT_ID, 1L, false))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
    }

}
