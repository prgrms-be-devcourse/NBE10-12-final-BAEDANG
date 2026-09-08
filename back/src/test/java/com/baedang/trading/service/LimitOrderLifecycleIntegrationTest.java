package com.baedang.trading.service;

import com.baedang.account.service.AccountResetService;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.trading.dto.LimitOrderRequest;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.repository.TradeExecutionRepository;
import com.baedang.trading.repository.TradeOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(LimitOrderLifecycleIntegrationTest.Time.class)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
class LimitOrderLifecycleIntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-07T01:00:00Z");
    static final AtomicReference<Instant> time = new AtomicReference<>(NOW);

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        Clock testClock() {
            return new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId zone) {
                    return Clock.fixed(instant(), zone);
                }

                public Instant instant() {
                    return time.get();
                }
            };
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    @MockitoBean MarketSessionProvider sessions;
    @MockitoBean ExecutionExchangeRateProvider rates;
    @MockitoBean MarketCalendarPort calendars;
    @MockitoBean com.baedang.trading.scheduler.LimitOrderExpirationScheduler scheduledTriggers;

    @Autowired LimitOrderService service;
    @Autowired OrderReadService reads;
    @Autowired LimitOrderExpirationService expiration;
    @Autowired TradeOrderRepository orders;
    @Autowired TradeExecutionRepository executions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AccountResetService resets;

    long user;
    long account;
    long stock;
    String symbol;

    @BeforeEach
    void setup() {
        time.set(NOW);
        when(sessions.currentSession(any(), any())).thenReturn(new MarketSessionStatus(true, NOW.plusSeconds(3600)));
        when(rates.currentUsdKrwSnapshot()).thenReturn(new ExecutionExchangeRateSnapshot(
                new BigDecimal("1400"),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(60).atOffset(ZoneOffset.UTC)
        ));
        symbol = UUID.randomUUID().toString().substring(0, 8);
        user = jdbc.queryForObject("INSERT INTO users(email,password_hash,nickname) VALUES (?,'x',?) RETURNING user_id", Long.class, symbol + "@test.com", symbol);
        account = jdbc.queryForObject("INSERT INTO account(user_id,initial_cash,cash_balance,opened_at) VALUES (?,50000000,50000000,?) RETURNING account_id", Long.class, user, NOW.minusSeconds(1).atOffset(ZoneOffset.UTC));
        stock = jdbc.queryForObject("INSERT INTO stock(symbol,market_country,market,name,currency,security_type,is_ranked) VALUES (?,'US','NASDAQ','test','USD','STOCK',true) RETURNING stock_id", Long.class, symbol);
        jdbc.update("INSERT INTO quote_snapshot(stock_id,last_price,currency,quote_at,collected_at) VALUES (?,100,'USD',?,?)", stock, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
    }

    LimitOrderRequest request(String side, String qty, String price, String currency) {
        return new LimitOrderRequest(account, UUID.randomUUID().toString(), symbol, "US", side, qty, price, currency);
    }

    BigDecimal locked() {
        return jdbc.queryForObject("SELECT locked_cash FROM account WHERE account_id=?", BigDecimal.class, account);
    }

    private void assertNoOrderEffects() {
        assertThat(locked()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_order WHERE account_id=?", Long.class, account)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE account_id=?", Long.class, account)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"NOT_IN_UNIVERSE,BUY", "STOCK_SUSPENDED,BUY", "MARKET_CLOSED,BUY",
            "QUOTE_CURRENCY_MISMATCH,BUY", "STALE_QUOTE,BUY", "FUTURE_QUOTE,BUY",
            "INSUFFICIENT_CASH,BUY", "INSUFFICIENT_QUANTITY,SELL"})
    void 실행불가_견적은_사유와_추정액을_반환하되_자원을_변경하지_않는다(ErrorCode expected, String side) {
        switch (expected) {
            case NOT_IN_UNIVERSE -> jdbc.update("UPDATE stock SET is_ranked=false WHERE stock_id=?", stock);
            case STOCK_SUSPENDED -> jdbc.update("UPDATE stock SET is_suspended=true WHERE stock_id=?", stock);
            case MARKET_CLOSED -> when(sessions.currentSession(any(), any())).thenReturn(new MarketSessionStatus(false, null));
            case QUOTE_CURRENCY_MISMATCH -> jdbc.update("UPDATE quote_snapshot SET currency='KRW' WHERE stock_id=?", stock);
            case STALE_QUOTE -> jdbc.update("UPDATE quote_snapshot SET quote_at=? WHERE stock_id=?", NOW.minusSeconds(60).atOffset(ZoneOffset.UTC), stock);
            case FUTURE_QUOTE -> jdbc.update("UPDATE quote_snapshot SET quote_at=? WHERE stock_id=?", NOW.plusSeconds(1).atOffset(ZoneOffset.UTC), stock);
            case INSUFFICIENT_CASH -> jdbc.update("UPDATE account SET cash_balance=1 WHERE account_id=?", account);
            case INSUFFICIENT_QUANTITY -> { }
            default -> throw new IllegalArgumentException("Unexpected scenario");
        }
        BigDecimal cashBefore = jdbc.queryForObject("SELECT cash_balance FROM account WHERE account_id=?", BigDecimal.class, account);
        var quote = service.quote(user, symbol, "US", side, "1", "100", "USD");
        assertThat(quote.acceptable()).isFalse();
        assertThat(quote.reason()).isEqualTo(expected);
        assertThat(quote.limitPrice()).isEqualTo("100.00");
        assertThat(quote.limitEstimate().grossAmount()).isEqualTo("140000");
        assertThat(quote.limitEstimate().netAmount()).isEqualTo("BUY".equals(side) ? "140014" : "139972");
        assertThat(quote.executionPreview()).containsEntry("status", "UNSUPPORTED");
        if (expected == ErrorCode.MARKET_CLOSED) assertThat(quote.expiresAt()).isNull();
        assertNoOrderEffects();
        assertThat(jdbc.queryForObject("SELECT cash_balance FROM account WHERE account_id=?", BigDecimal.class, account))
                .isEqualByComparingTo(cashBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"calendar", "rate", "missing-rate"})
    void 외부조회_실패는_같은ID_재시도를_허용하고_복구후_한번만_동결한다(String failure) {
        var request = request("BUY", "1", "100", "USD");
        if (failure.equals("calendar")) {
            when(sessions.currentSession(any(), any())).thenThrow(new BusinessException(ErrorCode.TOSS_API_ERROR));
        } else if (failure.equals("rate")) {
            when(rates.currentUsdKrwSnapshot()).thenThrow(new BusinessException(ErrorCode.TOSS_API_ERROR));
        } else {
            when(rates.currentUsdKrwSnapshot()).thenReturn(null);
        }
        assertThatThrownBy(() -> service.place(user, request)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(failure.equals("missing-rate") ? ErrorCode.EXCHANGE_RATE_NOT_FOUND : ErrorCode.TOSS_API_ERROR);
            assertThat(e.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
        });
        assertNoOrderEffects();
        org.mockito.Mockito.doReturn(new MarketSessionStatus(true, NOW.plusSeconds(3600))).when(sessions).currentSession(any(), any());
        org.mockito.Mockito.doReturn(new ExecutionExchangeRateSnapshot(new BigDecimal("1400"),
                NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), NOW.plusSeconds(60).atOffset(ZoneOffset.UTC)))
                .when(rates).currentUsdKrwSnapshot();
        var accepted = service.place(user, request);
        assertThat(accepted.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(service.place(user, request)).isEqualTo(accepted);
        assertThat(locked()).isEqualByComparingTo("140014");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_order WHERE account_id=?", Long.class, account)).isEqualTo(1L);
    }

    @Test
    void 정적_거절은_외부호출과_주문저장없이_같은ID_재시도를_안내한다() {
        jdbc.update("UPDATE stock SET is_suspended=true WHERE stock_id=?", stock);
        clearInvocations(sessions, rates);
        var request = request("BUY", "1", "100", "USD");
        assertThatThrownBy(() -> service.place(user, request)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_SUSPENDED);
            assertThat(e.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
        });
        verifyNoInteractions(sessions, rates);
        assertNoOrderEffects();
        jdbc.update("UPDATE stock SET is_suspended=false WHERE stock_id=?", stock);
        assertThat(service.place(user, request).status()).isEqualTo(OrderStatus.PENDING);
    }

    @ParameterizedTest
    @CsvSource({"KRW,1", "USD,999999999999999"})
    void 센트환산_0이나_정산범위초과는_주문을_저장하지_않는다(String currency, String price) {
        var invalid = request("BUY", "1", price, currency);
        assertThatThrownBy(() -> service.place(user, invalid)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_SETTLEMENT_AMOUNT);
            assertThat(e.getData()).containsEntry("retryPolicy", "SAME_CLIENT_ORDER_ID");
        });
        assertNoOrderEffects();
        var corrected = new LimitOrderRequest(account, invalid.clientOrderId(), symbol, "US", "BUY", "1", "100", "USD");
        assertThat(service.place(user, corrected).status()).isEqualTo(OrderStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void 주문과_체결목록은_페이지크기_범위밖을_거절한다(int size) {
        var order = service.place(user, request("BUY", "1", "100", "USD"));
        assertThatThrownBy(() -> reads.list(user, null, size)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> reads.executions(user, order.orderId(), null, size)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"too-long", "zero", "negative", "other-scope", "sequence-overflow"})
    void 잘못된_커서의_범위와_식별자를_거절한다(String scenario) {
        var order = service.place(user, request("BUY", "1", "100", "USD"));
        String value = switch (scenario) { case "zero" -> "0"; case "negative" -> "-1"; default -> "2147483648"; };
        String scope = scenario.equals("other-scope") ? "orders:" + account : "executions:" + order.orderId();
        String cursor = scenario.equals("too-long") ? "x".repeat(129)
                : Base64.getUrlEncoder().withoutPadding().encodeToString((scope + ":" + value).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> reads.executions(user, order.orderId(), cursor, 20)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURSOR));
    }

    @ParameterizedTest
    @CsvSource({"KRW,140001,100.00,140015", "USD,100.01,100.01,140028", "USD,100,100.00,140014"})
    void 입력통화별_환산과_동결액을_보존하고_동일요청은_환율없이_재생한다(String currency, String price, String usd, String reserve) {
        var request = request("BUY", "1", price, currency);
        var result = service.place(user, request);
        assertThat(result.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.limitPrice()).isEqualTo(usd);
        assertThat(locked()).isEqualByComparingTo(reserve);
        assertThat(result.requestedLimitCurrency()).isEqualTo(currency);
        assertThat(result.requestedLimitPrice()).isEqualTo("USD".equals(currency) ? usd : price);
        assertThat(result.activeRemainingQuantity()).isEqualTo("1");
        var quote = service.quote(user, symbol, "US", "BUY", "1", price, currency);
        assertThat(quote.requestedLimitPrice()).isEqualTo(result.requestedLimitPrice());
        assertThat(quote.limitPrice()).isEqualTo(result.limitPrice());
        assertThat(result.symbol()).isEqualTo(symbol);
        assertThat(result.name()).isEqualTo("test");
        assertThat(result.marketCountry().name()).isEqualTo("US");
        assertThat(reads.detail(user, result.orderId())).isEqualTo(result);
        assertThat(reads.list(user, null, 20).items()).containsExactly(result);
        assertThat(new BigDecimal(result.requestedLimitPrice())).isEqualByComparingTo(price);
        assertThat(executions.countByOrderId(result.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE account_id=?", Long.class, account)).isZero();

        clearInvocations(rates, sessions);
        assertThat(service.place(user, request)).isEqualTo(result);
        verifyNoInteractions(rates, sessions);

        service.cancel(user, result.orderId());
        assertThat(locked()).isZero();
        assertThat(service.cancel(user, result.orderId()).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(service.place(user, request).status()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    void 같은센트로_환산되는_다른원화입력도_멱등충돌이다() {
        var r = request("BUY", "1", "140001", "KRW");
        service.place(user, r);
        var changed = new LimitOrderRequest(account, r.clientOrderId(), symbol, "US", "BUY", "1", "140002", "KRW");
        assertThatThrownBy(() -> service.place(user, changed))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER));
    }

    @Test
    void 매도접수는_수량만_동결하고_취소는_보유수량을_보존한다() {
        jdbc.update("INSERT INTO holding(account_id,stock_id,quantity,avg_buy_price,avg_exchange_rate,usd_purchase_amount,krw_purchase_amount) VALUES (?,?,3,100,1400,300,420000)", account, stock);
        var result = service.place(user, request("SELL", "2", "100", "USD"));
        assertThat(jdbc.queryForObject("SELECT locked_quantity FROM holding WHERE account_id=?", BigDecimal.class, account)).isEqualByComparingTo("2");

        service.cancel(user, result.orderId());
        assertThat(jdbc.queryForObject("SELECT quantity FROM holding WHERE account_id=?", BigDecimal.class, account)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT locked_quantity FROM holding WHERE account_id=?", BigDecimal.class, account)).isZero();
    }

    @Test
    void 마감시각의_취소는_만료와_해제를_커밋한뒤_충돌을_반환한다() {
        var result = service.place(user, request("BUY", "1", "100", "USD"));
        time.set(NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.cancel(user, result.orderId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getData().get("status")).isEqualTo("EXPIRED"));
        assertThat(orders.findById(result.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(locked()).isZero();
    }

    @Test
    void 동결해제_실패는_주문종료를_롤백하고_후속주문처리와_복구후재시도를_허용한다() {
        com.baedang.trading.dto.OrderDetailResponse first = service.place(user, request("BUY", "2", "100", "USD"));
        com.baedang.trading.dto.OrderDetailResponse second = service.place(user, request("BUY", "1", "100", "USD"));
        jdbc.update("UPDATE account SET locked_cash=140014 WHERE account_id=?", account);
        time.set(NOW.plusSeconds(3601));

        expiration.expireDue();

        assertThat(reads.detail(user, first.orderId()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(reads.detail(user, first.orderId()).reservedCash()).isEqualTo("280028");
        assertThat(reads.detail(user, second.orderId()).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(locked()).isZero();
        jdbc.update("UPDATE account SET locked_cash=280028 WHERE account_id=?", account);

        expiration.expireDue();
        expiration.expireDue();

        assertThat(reads.detail(user, first.orderId()).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(locked()).isZero();
        assertThat(jdbc.queryForObject("SELECT cash_balance FROM account WHERE account_id=?", BigDecimal.class, account))
                .isEqualByComparingTo("50000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE account_id=?", Long.class, account)).isZero();
    }

    @Test
    void 매도_동결해제_실패는_종료를_롤백하고_복구후_한번만_해제한다() {
        jdbc.update("INSERT INTO holding(account_id,stock_id,quantity,avg_buy_price,avg_exchange_rate,usd_purchase_amount,krw_purchase_amount) VALUES (?,?,3,100,1400,300,420000)", account, stock);
        com.baedang.trading.dto.OrderDetailResponse first = service.place(user, request("SELL", "2", "100", "USD"));
        com.baedang.trading.dto.OrderDetailResponse second = service.place(user, request("SELL", "1", "100", "USD"));
        jdbc.update("UPDATE holding SET locked_quantity=1 WHERE account_id=? AND stock_id=?", account, stock);
        time.set(NOW.plusSeconds(3601));

        expiration.expireDue();

        com.baedang.trading.dto.OrderDetailResponse failed = reads.detail(user, first.orderId());
        assertThat(failed.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(failed.activeRemainingQuantity()).isEqualTo("2");
        assertThat(failed.closedAt()).isNull();
        assertThat(reads.detail(user, second.orderId()).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(jdbc.queryForObject("SELECT locked_quantity FROM holding WHERE account_id=? AND stock_id=?", BigDecimal.class, account, stock)).isZero();
        jdbc.update("UPDATE holding SET locked_quantity=2 WHERE account_id=? AND stock_id=?", account, stock);

        expiration.expireDue();
        expiration.expireDue();

        assertThat(reads.detail(user, first.orderId()).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(jdbc.queryForObject("SELECT locked_quantity FROM holding WHERE account_id=? AND stock_id=?", BigDecimal.class, account, stock)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM holding WHERE account_id=? AND stock_id=?", BigDecimal.class, account, stock)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT usd_purchase_amount FROM holding WHERE account_id=? AND stock_id=?", BigDecimal.class, account, stock)).isEqualByComparingTo("300");
        assertThat(jdbc.queryForObject("SELECT krw_purchase_amount FROM holding WHERE account_id=? AND stock_id=?", BigDecimal.class, account, stock)).isEqualByComparingTo("420000");
        assertThat(locked()).isZero();
        assertThat(jdbc.queryForObject("SELECT cash_balance FROM account WHERE account_id=?", BigDecimal.class, account)).isEqualByComparingTo("50000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE account_id=?", Long.class, account)).isZero();
    }

    @Test
    void 만료스캔은_중단시간동안_지난주문도_복구하고_반복해제하지_않는다() {
        var result = service.place(user, request("BUY", "1", "100", "USD"));
        time.set(NOW.plusSeconds(86400));
        expiration.expireDue();
        expiration.expireDue();

        assertThat(orders.findById(result.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(locked()).isZero();
        assertThat(jdbc.queryForObject("SELECT cash_balance FROM account WHERE account_id=?", BigDecimal.class, account)).isEqualByComparingTo("50000000");
    }

    @Test
    void 자원부족은_거절기록만_남긴다() {
        assertThatThrownBy(() -> service.place(user, request("BUY", "1000", "100", "USD")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getData().get("retryPolicy")).isEqualTo("NEW_CLIENT_ORDER_ID"));
        assertThat(locked()).isZero();
        assertThat(reads.list(user, null, 20).items().getFirst().status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void 백건을_넘는_만료주문도_모두_해제한다() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            ids.add(service.place(user, request("BUY", "1", "100", "USD")).orderId());
        }
        time.set(NOW.plusSeconds(3600));
        expiration.expireDue();
        assertThat(orders.findAllById(ids)).allSatisfy(o -> {
            assertThat(o.getStatus()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(o.getReservedCash()).isEqualByComparingTo("0");
        });
        assertThat(locked()).isZero();
    }

    @Test
    void 취소와_만료가_경합해도_한번만_해제한다() throws Exception {
        var order = service.place(user, request("BUY", "1", "100", "USD"));
        time.set(NOW.plusSeconds(3600));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancel = pool.submit(() -> {
                start.await();
                assertThatThrownBy(() -> service.cancel(user, order.orderId()))
                        .isInstanceOfSatisfying(BusinessException.class,
                                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_STATE_CONFLICT));
                return null;
            });
            var expire = pool.submit(() -> { start.await(); expiration.expireDue(); return null; });
            start.countDown();
            cancel.get(10, TimeUnit.SECONDS);
            expire.get(10, TimeUnit.SECONDS);
        }
        assertThat(orders.findById(order.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(locked()).isZero();
        assertThat(jdbc.queryForObject("SELECT cash_balance FROM account WHERE account_id=?", BigDecimal.class, account))
                .isEqualByComparingTo("50000000");
    }

    @Test
    void 잠금실패_주문을_건너뛰고_다음스캔에서_복구한다() throws Exception {
        var blocked = service.place(user, request("BUY", "1", "100", "USD"));
        long otherUser = jdbc.queryForObject("INSERT INTO users(email,password_hash,nickname) VALUES (?,'x','other') RETURNING user_id",
                Long.class, UUID.randomUUID() + "@test.com");
        long otherAccount = jdbc.queryForObject("INSERT INTO account(user_id,initial_cash,cash_balance,opened_at) VALUES (?,50000000,50000000,?) RETURNING account_id",
                Long.class, otherUser, NOW.atOffset(ZoneOffset.UTC));
        var next = service.place(otherUser, new LimitOrderRequest(otherAccount, UUID.randomUUID().toString(),
                symbol, "US", "BUY", "1", "100", "USD"));
        time.set(NOW.plusSeconds(3600));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT account_id FROM account WHERE account_id=? FOR UPDATE", Long.class, account);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test lock release timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                pool.submit(expiration::expireDue).get(8, TimeUnit.SECONDS);
                assertThat(orders.findById(blocked.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
                assertThat(orders.findById(next.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
                assertThat(locked()).isEqualByComparingTo("140014");
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
        expiration.expireDue();
        assertThat(orders.findById(blocked.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(locked()).isZero();
    }

    @Test
    void 주문목록_다음페이지는_중복과_누락없이_내림차순으로_이어진다() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(service.place(user, request("BUY", "1", "100", "USD")).orderId());
        var first = reads.list(user, null, 2);
        var second = reads.list(user, first.nextCursor(), 2);
        var third = reads.list(user, second.nextCursor(), 2);
        List<Long> actual = new ArrayList<>();
        for (var page : List.of(first, second, third)) page.items().forEach(o -> actual.add(o.orderId()));
        assertThat(actual).containsExactlyElementsOf(ids.reversed());
        assertThat(first.hasNext()).isTrue();
        assertThat(second.hasNext()).isTrue();
        assertThat(third.hasNext()).isFalse();
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void 체결목록_다음페이지는_순서와_원장잔액을_보존한다() {
        var order = service.place(user, request("BUY", "3", "100", "USD"));
        // 조회 전용 픽스처: 1주씩 3번 체결된 이력과 각 체결 직후 원장을 적재합니다.
        for (int sequence = 1; sequence <= 3; sequence++) {
            var at = NOW.plusSeconds(sequence).atOffset(ZoneOffset.UTC);
            long execution = insertExecution(order.orderId(), sequence);
            jdbc.update("""
                    INSERT INTO ledger_entry(account_id,order_id,execution_id,entry_type,amount,balance_after,exchange_rate,occurred_at)
                    VALUES (?,?,?,'BUY',-140014,?,1400,?)
                    """, account, order.orderId(), execution, 50000000 - sequence * 140014, at);
        }
        jdbc.update("""
                UPDATE trade_order SET status='FILLED',filled_quantity=3,execution_count=3,
                gross_amount=420000,fee=42,tax=0,net_amount=420042,reserved_cash=0,
                last_executed_at=?,closed_at=? WHERE order_id=?
                """, NOW.plusSeconds(3).atOffset(ZoneOffset.UTC), NOW.plusSeconds(3).atOffset(ZoneOffset.UTC), order.orderId());
        jdbc.update("UPDATE account SET cash_balance=49579958,locked_cash=0 WHERE account_id=?", account);
        var first = reads.executions(user, order.orderId(), null, 2);
        var second = reads.executions(user, order.orderId(), first.nextCursor(), 2);
        assertThat(first.items()).extracting(e -> e.sequenceNo()).containsExactly(1, 2);
        assertThat(second.items()).extracting(e -> e.sequenceNo()).containsExactly(3);
        assertThat(first.items()).extracting(e -> e.balanceAfter()).containsExactly("49859986", "49719972");
        assertThat(second.items().getFirst().balanceAfter()).isEqualTo("49579958");
        assertThat(first.stock()).isEqualTo(second.stock());
        assertThat(first.hasNext()).isTrue();
        assertThat(second.hasNext()).isFalse();
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void 소유권과_커서범위를_검증한다() {
        var result = service.place(user, request("BUY", "1", "100", "USD"));
        assertThatThrownBy(() -> reads.detail(user + 100000, result.orderId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        var executionPage = reads.executions(user, result.orderId(), null, 20);
        assertThat(executionPage.items()).isEmpty();
        assertThat(executionPage.orderId()).isEqualTo(result.orderId());
        assertThat(executionPage.stock().symbol()).isEqualTo(symbol);
        assertThat(executionPage.hasNext()).isFalse();
        assertThat(executionPage.nextCursor()).isNull();
        assertThatThrownBy(() -> reads.list(user, "invalid", 20))
                .isInstanceOf(BusinessException.class);
    }

    private long insertExecution(long orderId, int sequence) {
        var at = NOW.plusSeconds(sequence).atOffset(ZoneOffset.UTC);
        long bookLevelId = insertBookLevel(orderId, sequence);
        return jdbc.queryForObject("""
                INSERT INTO trade_execution(order_id,execution_key,sequence_no,quantity,price,exchange_rate,
                sec_fee_usd,gross_amount_krw,fee_krw,tax_krw,net_amount_krw,quote_at,executed_at,book_level_id)
                VALUES (?,?,?,1,100,1400,0,140000,14,0,140014,?,?,?) RETURNING execution_id
                """, Long.class, orderId, UUID.randomUUID(), sequence, at, at, bookLevelId);
    }

    /** LIMIT 체결 fixture도 운영 FK와 동일하게 실제 ASK 레벨을 참조한다. */
    private long insertBookLevel(long orderId, int depth) {
        Long stockId = jdbc.queryForObject(
                "SELECT stock_id FROM trade_order WHERE order_id = ?", Long.class, orderId);
        Long versionId = jdbc.query("""
                        SELECT book_version_id
                          FROM order_book_version
                         WHERE stock_id = ? AND is_active = true
                        """,
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                stockId);
        if (versionId == null) {
            versionId = jdbc.queryForObject("""
                    INSERT INTO order_book_version
                        (stock_id, base_price, currency, quote_at, generated_at, policy_version, seed, revision)
                    SELECT stock_id, 100, currency, ?, ?, 'V1', 1, 0
                      FROM stock
                     WHERE stock_id = ?
                    RETURNING book_version_id
                    """, Long.class, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), stockId);
        }
        return jdbc.queryForObject("""
                INSERT INTO order_book_level
                    (book_version_id, side, level_depth, price, initial_quantity, remaining_quantity)
                VALUES (?, 'ASK', ?, 100, 10, 10)
                RETURNING level_id
                """, Long.class, versionId, depth);
    }

    @Test
    void 체결원장이_누락되면_현재잔액으로_대체하지_않고_오류를_반환한다() {
        var order = service.place(user, request("BUY", "1", "100", "USD"));
        insertExecution(order.orderId(), 1);
        assertThatThrownBy(() -> reads.executions(user, order.orderId(), null, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void 동시_동일ID_접수는_한번만_동결한다() throws Exception {
        var request = request("BUY", "1", "100", "USD");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.place(user, request));
            var second = pool.submit(() -> service.place(user, request));
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(orders.countByAccountId(account)).isEqualTo(1);
        assertThat(locked()).isEqualByComparingTo("140014");
    }

    @Test
    void 접수_INSERT_실패는_현금동결도_롤백한다() {
        jdbc.execute("ALTER TABLE trade_order ADD CONSTRAINT test_limit_failure CHECK (account_id <> " + account + ")");
        try {
            assertThatThrownBy(() -> service.place(user, request("BUY", "1", "100", "USD")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(locked()).isZero();
            assertThat(orders.countByAccountId(account)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE trade_order DROP CONSTRAINT test_limit_failure");
        }
    }

    @Test
    void 초기화는_동결중_차단하고_취소후_과거주문_재요청은_보존한다() {
        var request = request("BUY", "1", "100", "USD");
        var result = service.place(user, request);
        assertThatThrownBy(() -> resets.reset(user, account))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_HAS_PENDING_ORDERS));

        service.cancel(user, result.orderId());
        resets.reset(user, account);
        assertThat(service.place(user, request).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(reads.list(user, null, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.place(user, request("BUY", "1", "100", "USD")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_ROUND_CHANGED));
    }

    @Test
    void 부분체결_픽스처의_취소는_잔여동결만_해제하고_누적결과를_보존한다() {
        var result = service.place(user, request("BUY", "3", "100", "USD"));
        // 이미 워커가 1주를 체결한 상태의 픽스처. 워커 알고리즘을 이 테스트에서 흉내 내지 않습니다.
        jdbc.update("UPDATE trade_order SET status='PARTIALLY_FILLED',filled_quantity=1,execution_count=1,last_executed_at=ordered_at,gross_amount=140000,fee=14,tax=0,net_amount=140014,reserved_cash=280028 WHERE order_id=?", result.orderId());
        jdbc.update("UPDATE account SET cash_balance=49859986,locked_cash=280028 WHERE account_id=?", account);
        var closed = service.cancel(user, result.orderId());
        assertThat(closed.filledQuantity()).isEqualTo("1");
        assertThat(closed.activeRemainingQuantity()).isEqualTo("0");
        assertThat(closed.netAmount()).isEqualTo("140014");
        assertThat(locked()).isZero();
        assertThat(jdbc.queryForObject("SELECT cash_balance FROM account WHERE account_id=?", BigDecimal.class, account)).isEqualByComparingTo("49859986");
    }

    @Test
    void 국내_견적과_접수는_외부환율을_조회하지_않는다() {
        jdbc.update("UPDATE stock SET market_country='KR',market='KOSPI',currency='KRW' WHERE stock_id=?", stock);
        jdbc.update("UPDATE quote_snapshot SET currency='KRW' WHERE stock_id=?", stock);
        clearInvocations(rates);

        var quote = service.quote(user, symbol, "KR", "BUY", "1", "1000", "KRW");
        assertThat(quote.acceptable()).isTrue();
        assertThat(quote.executionPreview()).containsEntry("status", "UNSUPPORTED");
        assertThat(locked()).isZero();

        service.place(user, new LimitOrderRequest(account, UUID.randomUUID().toString(), symbol, "KR", "BUY", "1", "1000", "KRW"));
        verifyNoInteractions(rates);
    }

    @Test
    void 상위트랜잭션에서_접수하면_외부호출전에_거절한다() {
        clearInvocations(rates, sessions);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(s -> service.place(user, request("BUY", "1", "100", "USD"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(rates, sessions);
    }
}
