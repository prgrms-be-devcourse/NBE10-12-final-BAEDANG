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
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql").toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql"
            );

    @MockitoBean MarketSessionProvider sessions;
    @MockitoBean ExecutionExchangeRateProvider rates;
    @MockitoBean MarketCalendarPort calendars;

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
