package com.baedang.trading.service;

import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.ExecutionExchangeRateSnapshot;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.orderbook.support.MutableClock;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.GeneratedOrderBookLevel;
import com.baedang.orderbook.service.OrderBookPublicationService;
import com.baedang.stock.service.StockTradingStatusService;
import com.baedang.trading.dto.LimitOrderRequest;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.LimitExecutionAttempt;
import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionOutcome;
import com.baedang.trading.model.LimitExecutionPreparation;
import com.baedang.trading.model.LimitOrderCommand;
import com.baedang.trading.model.OrderTerms;
import com.baedang.trading.model.OrderMarketContext;
import com.baedang.trading.repository.LimitExecutionCandidateRepository;
import com.baedang.trading.repository.LimitExecutionCandidateRepository.Group;
import com.baedang.trading.scheduler.LimitExecutionProgress;
import com.baedang.trading.scheduler.LimitOrderExecutionWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.baedang.trading.repository.TradeOrderRepository;
import com.baedang.stock.repository.StockRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never", "toss.enabled=false"})
class LimitOrderExecutionIntegrationTest {
    static final Instant NOW = Instant.parse("2026-09-09T01:00:00Z");
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:latest-pg18").asCompatibleSubstituteFor("postgres"));
    @TestConfiguration
    static class Time {
        @Bean @Primary MutableClock executionTestClock() { return new MutableClock(NOW); }
    }
    @MockitoBean MarketSessionProvider sessions;
    @MockitoBean ExecutionExchangeRateProvider rates;
    @MockitoBean com.baedang.market.port.MarketCalendarPort calendars;
    @MockitoBean com.baedang.market.port.MarketDataPort marketData;
    @MockitoBean StockTradingStatusService statuses;
    @MockitoBean com.baedang.trading.scheduler.LimitOrderExpirationScheduler expirationTrigger;
    @MockitoSpyBean LedgerService ledger;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired LimitOrderService admission;
    @Autowired LimitOrderExecutionService execution;
    @Autowired LimitOrderExecutionTransactionService transactions;
    @Autowired LimitOrderTransactionService lifecycle;
    @Autowired OrderBookPublicationService publication;
    @Autowired LimitExecutionProgress progress;
    @Autowired LimitOrderPricing pricing;
    @Autowired LimitExecutionBookReader books;
    @Autowired TradeOrderRepository orders;
    @Autowired StockRepository stocks;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.baedang.trading.repository.LimitExecutionCandidateRepository candidates;
    long user, account, stock, version;
    String symbol;

    @BeforeEach
    void setup() {
        clock.setCurrent(NOW);
        when(sessions.currentSession(any(),any())).thenReturn(new MarketSessionStatus(true,NOW.plusSeconds(3600)));
        when(statuses.requireCurrent(any())).thenAnswer(inv -> inv.getArgument(0));
        rate("1400");
        symbol = UUID.randomUUID().toString().substring(0,8);
        user = jdbc.queryForObject("INSERT INTO users(email,password_hash,nickname) VALUES (?,'x',?) RETURNING user_id",Long.class,symbol+"@test.com",symbol);
        account = jdbc.queryForObject("INSERT INTO account(user_id,initial_cash,cash_balance,opened_at) VALUES (?,50000000,50000000,?) RETURNING account_id",Long.class,user,NOW.minusSeconds(1).atOffset(ZoneOffset.UTC));
        new TransactionTemplate(manager).executeWithoutResult(tx -> ledger.recordInitialDeposit(
                account,new BigDecimal("50000000"),1,NOW.minusSeconds(1).atOffset(ZoneOffset.UTC)));
        stock = jdbc.queryForObject("INSERT INTO stock(symbol,market_country,market,name,currency,security_type,is_ranked) VALUES (?,'US','NASDAQ','test','USD','STOCK',true) RETURNING stock_id",Long.class,symbol);
        jdbc.update("INSERT INTO quote_snapshot(stock_id,last_price,currency,quote_at,collected_at) VALUES (?,100,'USD',?,?)",stock,NOW.atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void 미리보기와_여러호가_실체결의_금액이_일치하고_원장별_잔액을_보존한다() {
        book("ASK", "99", 1, 2);
        com.baedang.trading.dto.LimitExecutionPreviewResponse preview = admission.quote(user,symbol,"US","BUY","3","100","USD").executionPreview();
        assertThat(preview.expectedFilledQuantity()).isEqualTo("3");
        assertThat(preview.avgExecutionPrice()).isEqualTo("99.67");
        assertThat(number("SELECT revision FROM order_book_version WHERE book_version_id=?",version)).isZero();
        long order = place("BUY","3","100");
        assertThat(execute(order).executionCount()).isEqualTo(2);
        assertThat(orders.findById(order).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(number("SELECT locked_cash FROM account WHERE account_id=?",account)).isZero();
        assertThat(number("SELECT net_amount FROM trade_order WHERE order_id=?",order)).isEqualByComparingTo(preview.netAmountKrw());
        assertThat(number("SELECT cash_balance FROM account WHERE account_id=?",account)).isEqualByComparingTo("49581358");
        assertThat(jdbc.queryForList("SELECT balance_after FROM ledger_entry WHERE order_id=? ORDER BY entry_id",BigDecimal.class,order))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("49861386"),new BigDecimal("49581358"));
        assertThat(number("SELECT usd_purchase_amount FROM holding WHERE account_id=?",account)).isEqualByComparingTo("299");
        assertThat(number("SELECT krw_purchase_amount FROM holding WHERE account_id=?",account)).isEqualByComparingTo("418600");
        assertThat(number("SELECT revision FROM order_book_version WHERE book_version_id=?",version)).isEqualByComparingTo("1");
        assertThat(number("SELECT count(*) FROM trade_execution WHERE order_id=? AND book_level_id IS NOT NULL",order)).isEqualByComparingTo("2");
        assertCashConservation();
    }

    @Test
    void 부분매수는_주문별동결만_사용하고_취소하면_잔여동결만_해제한다() {
        book("ASK","100",10,10);
        long order = place("BUY","3","100");
        long other = place("BUY","1","100");
        rate("1700");
        assertThat(execute(order).executionCount()).isEqualTo(1);
        assertThat(orders.findById(order).orElseThrow().getFilledQuantity()).isEqualByComparingTo("2");
        assertThat(orders.findById(order).orElseThrow().getReservedCash()).isEqualByComparingTo("80008");
        admission.cancel(user,order);
        assertThat(number("SELECT locked_cash FROM account WHERE account_id=?",account)).isEqualByComparingTo("140014");
        assertThat(orders.findById(other).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(number("SELECT count(*) FROM ledger_entry WHERE order_id=?",order)).isEqualByComparingTo("1");
        assertCashConservation();
    }

    @Test
    void 매도는_동결수량을_차감하고_SEC를_한번만_부과한다() {
        jdbc.update("INSERT INTO holding(account_id,stock_id,quantity,locked_quantity,avg_buy_price,avg_exchange_rate,usd_purchase_amount,krw_purchase_amount,updated_at) VALUES (?,?,3,0,90,1400,270,378000,?)",account,stock,NOW.atOffset(ZoneOffset.UTC));
        book("BID","100",1,1);
        long order = place("SELL","2","99");
        assertThat(execute(order).executionCount()).isEqualTo(2);
        assertThat(number("SELECT quantity FROM holding WHERE account_id=?",account)).isEqualByComparingTo("1");
        assertThat(number("SELECT locked_quantity FROM holding WHERE account_id=?",account)).isZero();
        assertThat(number("SELECT usd_purchase_amount FROM holding WHERE account_id=?",account)).isEqualByComparingTo("90");
        assertThat(number("SELECT sum(sec_fee_usd) FROM trade_execution WHERE order_id=?",order)).isEqualByComparingTo("0.01");
        assertThat(number("SELECT cash_balance FROM account WHERE account_id=?",account)).isEqualByComparingTo("50278558");
        assertThat(jdbc.queryForList("SELECT balance_after FROM ledger_entry WHERE order_id=? ORDER BY entry_id",BigDecimal.class,order))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("50139972"),new BigDecimal("50278558"));
        assertCashConservation();
        assertThat(number("SELECT locked_quantity FROM holding WHERE account_id=?",account))
                .isEqualByComparingTo(number("SELECT coalesce(sum(quantity-filled_quantity),0) FROM trade_order WHERE account_id=? AND side='SELL' AND status IN ('PENDING','PARTIALLY_FILLED')",account));
    }

    @Test
    void 두번째원장_실패는_앞선체결과_물량까지_모두_롤백한다() {
        book("ASK","99",1,2);
        long order = place("BUY","3","100");
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> { if (calls.incrementAndGet() == 2) throw new IllegalStateException("원장 저장 실패"); return inv.callRealMethod(); })
                .when(org.springframework.test.util.AopTestUtils.<LedgerService>getUltimateTargetObject(ledger))
                .recordBuy(any(),any(),any(),any());
        assertThatThrownBy(() -> execute(order)).isInstanceOf(IllegalStateException.class);
        assertThat(number("SELECT count(*) FROM trade_execution WHERE order_id=?",order)).isZero();
        assertThat(number("SELECT count(*) FROM ledger_entry WHERE order_id=?",order)).isZero();
        assertThat(number("SELECT revision FROM order_book_version WHERE book_version_id=?",version)).isZero();
        assertThat(number("SELECT remaining_quantity FROM order_book_level WHERE book_version_id=? AND level_depth=1",version)).isEqualByComparingTo("1");
        assertThat(number("SELECT cash_balance FROM account WHERE account_id=?",account)).isEqualByComparingTo("50000000");
        assertThat(number("SELECT locked_cash FROM account WHERE account_id=?",account)).isEqualByComparingTo("420042");
        assertThat(number("SELECT count(*) FROM holding WHERE account_id=?",account)).isZero();
    }

    @Test
    void 동일시도_재실행은_체결횟수_토큰으로_차단한다() {
        book("ASK","100",1,1);
        long order = place("BUY","3","100");
        LimitExecutionAttempt attempt = attempt(order);
        assertThat(transactions.execute(attempt).executionCount()).isEqualTo(1);
        assertThat(transactions.execute(attempt).reason()).isEqualTo(LimitExecutionOutcome.Reason.ORDER_CHANGED);
        assertThat(number("SELECT count(*) FROM trade_execution WHERE order_id=?",order)).isEqualByComparingTo("1");
    }

    @Test
    void 다른계좌가_같은호가를_소비하면_후속시도는_revision불일치로_차단된다() throws Exception {
        book("ASK","100",3,1);
        long first = place("BUY","3","100");
        long user2 = jdbc.queryForObject("INSERT INTO users(email,password_hash,nickname) VALUES (?,'x','second') RETURNING user_id",Long.class,UUID.randomUUID()+"@test.com");
        long account2 = jdbc.queryForObject("INSERT INTO account(user_id,initial_cash,cash_balance,opened_at) VALUES (?,50000000,50000000,?) RETURNING account_id",Long.class,user2,NOW.minusSeconds(1).atOffset(ZoneOffset.UTC));
        long second = admission.place(user2,new LimitOrderRequest(account2,UUID.randomUUID().toString(),symbol,"US","BUY","3","100","USD")).orderId();
        LimitExecutionAttempt a = attempt(first), b = attempt(second);
        try (java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<LimitExecutionOutcome> one = pool.submit(() -> { start.await(); return transactions.execute(a); });
            Future<LimitExecutionOutcome> two = pool.submit(() -> { start.await(); return transactions.execute(b); });
            start.countDown();
            List<LimitExecutionOutcome.Reason> reasons = List.of(one.get(10,TimeUnit.SECONDS).reason(),two.get(10,TimeUnit.SECONDS).reason());
            assertThat(reasons).containsExactlyInAnyOrder(LimitExecutionOutcome.Reason.EXECUTED,LimitExecutionOutcome.Reason.BOOK_CHANGED);
        }
        assertThat(number("SELECT sum(quantity) FROM trade_execution WHERE order_id IN (?,?)",first,second)).isEqualByComparingTo("3");
        assertThat(number("SELECT remaining_quantity FROM order_book_level WHERE book_version_id=? AND level_depth=1",version)).isZero();
    }

    @Test
    void 취소후_준비된시도와_장마감후_시도는_결제하지않는다() {
        book("ASK","100",3,1);
        long order = place("BUY","3","100");
        LimitExecutionAttempt attempt = attempt(order);
        admission.cancel(user,order);
        assertThat(transactions.execute(attempt).reason()).isEqualTo(LimitExecutionOutcome.Reason.INACTIVE);
        long expiring = place("BUY","1","100");
        LimitExecutionAttempt expiryAttempt = attempt(expiring);
        clock.setCurrent(NOW.plusSeconds(3600));
        assertThat(transactions.execute(expiryAttempt).reason()).isEqualTo(LimitExecutionOutcome.Reason.EXPIRED);
        assertThat(number("SELECT count(*) FROM trade_execution WHERE order_id IN (?,?)",order,expiring)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"BUY, true", "BUY, false", "SELL, true", "SELL, false"})
    void 취소와_부분체결의_계좌락_경합은_선행커밋에_따라_한번만_정산한다(OrderSide side, boolean executionFirst) throws Exception {
        if (side == OrderSide.SELL) {
            jdbc.update("INSERT INTO holding(account_id,stock_id,quantity,locked_quantity,avg_buy_price,avg_exchange_rate,usd_purchase_amount,krw_purchase_amount,updated_at) VALUES (?,?,2,0,90,1400,180,252000,?)",
                    account, stock, NOW.atOffset(ZoneOffset.UTC));
        }
        book(side == OrderSide.BUY ? "ASK" : "BID", "100", 1, 1);
        long order = place(side.name(), "2", "100");
        LimitExecutionAttempt prepared = attempt(order);
        AtomicReference<LimitExecutionOutcome> outcome = new AtomicReference<>();
        Runnable fill = () -> outcome.set(transactions.execute(prepared));
        Runnable cancel = () -> assertThat(lifecycle.close(user, account, order, false).status()).isEqualTo(OrderStatus.CANCELED);

        runContended(executionFirst ? fill : cancel, executionFirst ? cancel : fill);

        int filled = executionFirst ? 1 : 0;
        assertThat(outcome.get().reason()).isEqualTo(executionFirst
                ? LimitExecutionOutcome.Reason.EXECUTED : LimitExecutionOutcome.Reason.INACTIVE);
        assertThat(orders.findById(order).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(number("SELECT filled_quantity FROM trade_order WHERE order_id=?", order)).isEqualByComparingTo(BigDecimal.valueOf(filled));
        assertThat(number("SELECT count(*) FROM trade_execution WHERE order_id=?", order)).isEqualByComparingTo(BigDecimal.valueOf(filled));
        assertThat(number("SELECT count(*) FROM ledger_entry WHERE order_id=?", order)).isEqualByComparingTo(BigDecimal.valueOf(filled));
        assertThat(number("SELECT reserved_cash FROM trade_order WHERE order_id=?", order)).isZero();
        assertThat(number("SELECT locked_cash FROM account WHERE account_id=?", account)).isZero();
        assertThat(number("SELECT coalesce(sum(locked_quantity),0) FROM holding WHERE account_id=?", account)).isZero();
        assertThat(number("SELECT coalesce(sum(quantity),0) FROM holding WHERE account_id=? AND stock_id=?", account, stock))
                .isEqualByComparingTo(BigDecimal.valueOf(side == OrderSide.BUY ? filled : 2 - filled));
        assertThat(number("SELECT remaining_quantity FROM order_book_level WHERE book_version_id=? AND level_depth=1", version))
                .isEqualByComparingTo(BigDecimal.valueOf(1 - filled));
        assertThat(number("SELECT revision FROM order_book_version WHERE book_version_id=?", version)).isEqualByComparingTo(BigDecimal.valueOf(filled));
        BigDecimal expectedCash = new BigDecimal("50000000").add((side == OrderSide.BUY
                ? new BigDecimal("-140014") : new BigDecimal("139972")).multiply(BigDecimal.valueOf(filled)));
        assertThat(number("SELECT cash_balance FROM account WHERE account_id=?", account)).isEqualByComparingTo(expectedCash);
        assertCashConservation();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 호가교체와_체결의_버전락_경합은_폐쇄버전_추가소비를_막는다(boolean executionFirst) throws Exception {
        book("ASK", "100", 1, 1);
        long oldVersion = version;
        long order = place("BUY", "2", "100");
        LimitExecutionAttempt prepared = attempt(order);
        GeneratedOrderBook replacement = replacementBook();
        AtomicReference<LimitExecutionOutcome> outcome = new AtomicReference<>();
        AtomicReference<Long> newVersion = new AtomicReference<>();
        Runnable fill = () -> outcome.set(transactions.execute(prepared));
        Runnable replace = () -> newVersion.set(publication.publish(replacement, NOW.plusSeconds(3600)).orElseThrow());

        runContended(executionFirst ? fill : replace, executionFirst ? replace : fill);

        int oldFills = executionFirst ? 1 : 0;
        assertThat(outcome.get().reason()).isEqualTo(executionFirst
                ? LimitExecutionOutcome.Reason.EXECUTED : LimitExecutionOutcome.Reason.BOOK_CHANGED);
        assertThat(number("SELECT count(*) FROM order_book_version WHERE stock_id=? AND is_active", stock)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT is_active FROM order_book_version WHERE book_version_id=?", Boolean.class, oldVersion)).isFalse();
        assertThat(number("SELECT remaining_quantity FROM order_book_level WHERE book_version_id=? AND side='ASK' AND level_depth=1", oldVersion))
                .isEqualByComparingTo(BigDecimal.valueOf(1 - oldFills));
        assertThat(number("SELECT revision FROM order_book_version WHERE book_version_id=?", oldVersion))
                .isEqualByComparingTo(BigDecimal.valueOf(oldFills));
        assertThat(number("SELECT revision FROM order_book_version WHERE book_version_id=?", newVersion.get())).isZero();
        assertThat(number("SELECT count(*) FROM order_book_level WHERE book_version_id=? AND remaining_quantity<>initial_quantity", newVersion.get())).isZero();

        // 다음 정상 시도는 새 호가만 사용하고 이전 체결의 누적 금액을 이어받습니다.
        assertThat(execute(order).reason()).isEqualTo(LimitExecutionOutcome.Reason.EXECUTED);
        assertThat(orders.findById(order).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(number("SELECT sum(quantity) FROM trade_execution WHERE order_id=?", order)).isEqualByComparingTo("2");
        assertThat(number("SELECT coalesce(sum(e.quantity),0) FROM trade_execution e JOIN order_book_level l ON l.level_id=e.book_level_id WHERE e.order_id=? AND l.book_version_id=?", order, oldVersion))
                .isEqualByComparingTo(BigDecimal.valueOf(oldFills));
        assertThat(number("SELECT remaining_quantity FROM order_book_level WHERE book_version_id=? AND side='ASK' AND level_depth=1", newVersion.get()))
                .isEqualByComparingTo(BigDecimal.valueOf(1 + oldFills));
        assertThat(number("SELECT count(*) FROM ledger_entry WHERE order_id=?", order)).isEqualByComparingTo(BigDecimal.valueOf(oldFills + 1));
        assertThat(number("SELECT cash_balance FROM account WHERE account_id=?", account)).isEqualByComparingTo("49719972");
        assertThat(number("SELECT locked_cash FROM account WHERE account_id=?", account)).isZero();
        assertThat(number("SELECT quantity FROM holding WHERE account_id=? AND stock_id=?", account, stock)).isEqualByComparingTo("2");
        assertCashConservation();
    }

    private GeneratedOrderBook replacementBook() {
        return replacementBook("100");
    }

    private GeneratedOrderBook replacementBook(String firstAsk) {
        BigDecimal bestAsk = new BigDecimal(firstAsk);
        List<GeneratedOrderBookLevel> levels = new ArrayList<>();
        for (OrderBookSide side : List.of(OrderBookSide.ASK, OrderBookSide.BID)) {
            for (int depth = 1; depth <= 10; depth++) {
                BigDecimal distance = new BigDecimal("0.01").multiply(BigDecimal.valueOf(depth - 1));
                BigDecimal price = side == OrderBookSide.ASK ? bestAsk.add(distance) : bestAsk.subtract(new BigDecimal("0.02")).subtract(distance);
                levels.add(new GeneratedOrderBookLevel(side, depth, price, new BigDecimal("3")));
            }
        }
        return new GeneratedOrderBook(stock, bestAsk.subtract(new BigDecimal("0.01")), "USD", NOW, NOW, "test", 2L, levels);
    }

    /** 첫 서비스의 실제 트랜잭션을 유지하고, 두 번째 연결이 그 잠금을 기다린 것을 확인한 뒤 커밋합니다. */
    private void runContended(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> one = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                first.run();
                firstReady.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("선행 트랜잭션 해제 시간 초과");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            Future<?> two;
            try {
                assertThat(firstReady.await(5, TimeUnit.SECONDS)).as("선행 서비스 실행 완료").isTrue();
                two = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                    secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    secondStarted.countDown();
                    second.run();
                }));
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                            Boolean.class, firstPid.get(), secondPid.get()));
                    if (blocked) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
                assertThat(blocked).as("후행 DB 연결이 선행 트랜잭션의 잠금을 기다림").isTrue();
            } finally {
                release.countDown();
            }
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 락대기중_시세가_만료되면_결제하지않는다() throws Exception {
        book("ASK","100",3,1);
        jdbc.update("UPDATE order_book_version SET quote_at=? WHERE book_version_id=?",NOW.minusSeconds(14).atOffset(ZoneOffset.UTC),version);
        long order = place("BUY","1","100");
        LimitExecutionAttempt attempt = attempt(order);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> blocker = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT account_id FROM account WHERE account_id=? FOR UPDATE",Long.class,account);
                locked.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            Future<LimitExecutionOutcome> fill = pool.submit(() -> transactions.execute(attempt));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                waiting = jdbc.queryForObject("SELECT count(*) > 0 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%account%'",Boolean.class);
                if (waiting) break;
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            assertThat(waiting).isTrue();
            clock.setCurrent(NOW.plusSeconds(2));
            release.countDown();
            blocker.get(5,TimeUnit.SECONDS);
            assertThat(fill.get(5,TimeUnit.SECONDS).reason()).isEqualTo(LimitExecutionOutcome.Reason.STALE_BOOK);
        } finally { release.countDown(); }
        assertThat(number("SELECT count(*) FROM trade_execution WHERE order_id=?",order)).isZero();
    }

    @Test
    void 국내는_환율포트없이_체결하고_상위트랜잭션은_거부한다() {
        jdbc.update("UPDATE stock SET market_country='KR',market='KOSPI',currency='KRW' WHERE stock_id=?",stock);
        jdbc.update("UPDATE quote_snapshot SET currency='KRW' WHERE stock_id=?",stock);
        book("ASK","99",1,2);
        jdbc.update("UPDATE order_book_version SET currency='KRW' WHERE book_version_id=?",version);
        clearInvocations(rates);
        long order = admission.place(user,new LimitOrderRequest(account,UUID.randomUUID().toString(),symbol,"KR","BUY","3","100","KRW")).orderId();
        assertThat(execute(order).executionCount()).isEqualTo(2);
        verifyNoInteractions(rates);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> execute(order)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private LimitExecutionOutcome execute(long orderId) {
        com.baedang.trading.entity.TradeOrder order = orders.findById(orderId).orElseThrow();
        com.baedang.trading.model.LimitExecutionPreparation prepared = execution.prepare(order.getStockId(), order.getSide());
        return prepared.available() ? execution.execute(orderId, prepared) : LimitExecutionOutcome.deferred(prepared.reason());
    }

    private long place(String side,String quantity,String price) {
        return admission.place(user,new LimitOrderRequest(account,UUID.randomUUID().toString(),symbol,"US",side,quantity,price,"USD")).orderId();
    }

    @Test
    void 나노초_환율근거는_원래시각으로_검증하고_체결은_마이크로초로_저장한다() {
        book("ASK","100",3,1);
        long order = place("BUY","1","100");
        Instant prepared = NOW.plusNanos(789);
        clock.setCurrent(NOW.plusNanos(900));
        when(rates.currentUsdKrwSnapshot()).thenReturn(new ExecutionExchangeRateSnapshot(new BigDecimal("1400"),
                prepared.atOffset(ZoneOffset.UTC),prepared.atOffset(ZoneOffset.UTC),NOW.plusSeconds(60).atOffset(ZoneOffset.UTC)));
        assertThat(execute(order).executionCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT executed_at FROM trade_execution WHERE order_id=?",java.time.OffsetDateTime.class,order).toInstant()).isEqualTo(NOW);
    }

    @Test
    void 체결용환율_원본만료와_미래호가는_체결하지않는다() {
        book("ASK","100",3,1);
        long order = place("BUY","1","100");
        when(rates.currentUsdKrwSnapshot()).thenReturn(new ExecutionExchangeRateSnapshot(new BigDecimal("1400"),
                NOW.minusSeconds(10).atOffset(ZoneOffset.UTC),NOW.minusSeconds(10).atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC)));
        assertThat(execute(order).reason()).isEqualTo(LimitExecutionOutcome.Reason.CONTEXT_EXPIRED);
        jdbc.update("UPDATE order_book_version SET quote_at=?,generated_at=? WHERE book_version_id=?",NOW.plusSeconds(1).atOffset(ZoneOffset.UTC),NOW.plusSeconds(1).atOffset(ZoneOffset.UTC),version);
        clearInvocations(rates);
        assertThat(execute(order).reason()).isEqualTo(LimitExecutionOutcome.Reason.NO_BOOK);
        verifyNoInteractions(rates);
        assertThat(number("SELECT count(*) FROM trade_execution WHERE order_id=?",order)).isZero();
    }

    @Test
    void 충분한_대기열에서_가격우선_인덱스를_사용한다() {
        long sample = place("BUY","1","100");
        jdbc.update("""
                INSERT INTO trade_order(account_id,stock_id,client_order_id,side,order_type,quantity,status,
                  limit_price,requested_limit_price,requested_limit_currency,acceptance_exchange_rate,
                  reserved_cash,expires_at,ordered_at,gross_amount,fee,tax,net_amount)
                SELECT account_id,stock_id,gen_random_uuid(),side,order_type,quantity,status,
                  100 + i % 10,100 + i % 10,requested_limit_currency,acceptance_exchange_rate,
                  reserved_cash,expires_at,ordered_at,gross_amount,fee,tax,net_amount
                FROM trade_order CROSS JOIN generate_series(1,4000) i WHERE order_id=?
                """,sample);
        jdbc.execute("ANALYZE trade_order");
        List<String> plan = jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS) SELECT order_id,limit_price,ordered_at FROM trade_order
                WHERE order_type='LIMIT' AND status IN ('PENDING','PARTIALLY_FILLED')
                  AND quantity > filled_quantity AND expires_at > ?
                  AND stock_id=? AND side='BUY'
                ORDER BY limit_price DESC,ordered_at,order_id LIMIT 50
                """,String.class,NOW.atOffset(ZoneOffset.UTC),stock);
        assertThat(String.join("\n",plan)).contains("ix_order_execute_buy").doesNotContain("Seq Scan");
    }

    @Test
    void 후보쿼리는_가격시간순으로_페이지를_잇고_새선순위도_즉시_포함한다() {
        long lower = place("BUY","1","99");
        long high = place("BUY","1","101");
        long same = place("BUY","1","101");
        long newer = place("BUY","1","102");
        com.baedang.trading.repository.LimitExecutionCandidateRepository.Group group =
                new com.baedang.trading.repository.LimitExecutionCandidateRepository.Group(stock,OrderSide.BUY);
        List<com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate> page =
                candidates.page(group,null,NOW.atOffset(ZoneOffset.UTC),1);
        assertThat(page).extracting(com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate::orderId).containsExactly(newer);
        page = candidates.page(group,page.getLast(),NOW.atOffset(ZoneOffset.UTC),50);
        assertThat(page).extracting(com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate::orderId).containsExactly(high,same,lower);
        assertThat(candidates.page(group,null,NOW.atOffset(ZoneOffset.UTC),1).getFirst().orderId()).isEqualTo(newer);
    }

    @Test
    void 후보쿼리는_매도저가우선과_만료제외를_적용한다() {
        jdbc.update("INSERT INTO holding(account_id,stock_id,quantity,locked_quantity,avg_buy_price,avg_exchange_rate,usd_purchase_amount,krw_purchase_amount,updated_at) VALUES (?,?,10,0,90,1400,900,1260000,?)",account,stock,NOW.atOffset(ZoneOffset.UTC));
        long high = place("SELL","1","101");
        long low = place("SELL","1","99");
        com.baedang.trading.repository.LimitExecutionCandidateRepository.Group group =
                new com.baedang.trading.repository.LimitExecutionCandidateRepository.Group(stock,OrderSide.SELL);
        assertThat(candidates.page(group,null,NOW.atOffset(ZoneOffset.UTC),50))
                .extracting(com.baedang.trading.repository.LimitExecutionCandidateRepository.Candidate::orderId).containsExactly(low,high);
        assertThat(candidates.page(group,null,NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC),50)).isEmpty();
    }
    private void rate(String value) {
        when(rates.currentUsdKrwSnapshot()).thenReturn(new ExecutionExchangeRateSnapshot(new BigDecimal(value),NOW.atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC),NOW.plusSeconds(60).atOffset(ZoneOffset.UTC)));
    }

    @Test
    void 새호가물량은_다음틱에도_부분체결된_선순위에게_먼저_배정한다() {
        book("ASK", "100", 1, 1);
        long high = place("BUY", "2", "100");
        long low = place("BUY", "1", "99");
        LimitOrderExecutionWorker worker = scopedWorker();
        worker.tick();
        assertThat(orders.findById(high).orElseThrow().getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        publication.publish(replacementBook("99"), NOW.plusSeconds(3600)).orElseThrow();
        worker.tick();
        assertThat(orders.findById(high).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(orders.findById(low).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        worker.tick();
        assertThat(orders.findById(low).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(number("SELECT sum(quantity) FROM trade_execution WHERE order_id IN (?,?)", high, low)).isEqualByComparingTo("3");
        assertCashConservation();
    }

    @Test
    void 동일호가라도_환율하락시_동결부족인_선순위_잔여분을_먼저_체결한다() {
        book("ASK", "100", 3, 1);
        long high = place("BUY", "2", "101");
        long low = place("BUY", "1", "100");
        LimitOrderExecutionWorker worker = scopedWorker();
        rate("2500");
        worker.tick();
        assertThat(orders.findById(high).orElseThrow().getFilledQuantity()).isEqualByComparingTo("1");
        rate("300");
        worker.tick();
        assertThat(orders.findById(high).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(orders.findById(low).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(number("SELECT count(DISTINCT exchange_rate) FROM trade_execution WHERE order_id=?", high)).isEqualByComparingTo("2");
        assertCashConservation();
    }

    @Test
    void 접수롤백은_커서를_유지하고_새선순위_접수커밋은_다음틱에_반영한다() {
        book("ASK", "101", 3, 1);
        long low = place("BUY", "1", "100");
        LimitOrderExecutionWorker worker = scopedWorker();
        worker.tick();
        Group group = new Group(stock, OrderSide.BUY);
        LimitExecutionPreparation market = execution.prepare(stock, OrderSide.BUY);
        LimitExecutionProgress.Position before = progress.position(group, market);
        assertThat(before.after().orderId()).isEqualTo(low);
        LimitOrderCommand rolledBack = new LimitOrderCommand(account, UUID.randomUUID(),
                new OrderTerms(symbol, MarketCountry.US, OrderSide.BUY, BigDecimal.ONE), new BigDecimal("103"), "USD");
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            lifecycle.accept(user, rolledBack, market.context(), pricing.calculate(rolledBack, market.context().executionRate()));
            tx.setRollbackOnly();
        });
        assertThat(progress.isCurrent(group, before)).isTrue();
        assertThat(progress.position(group, market)).isEqualTo(before);
        assertThat(number("SELECT count(*) FROM trade_order WHERE client_order_id=?", rolledBack.clientOrderId())).isZero();

        long high = place("BUY", "1", "102");
        // 접수 커밋 자체는 진행 중 선정을 무효화하지 않고 다음 선정 경계에 반영됩니다.
        assertThat(progress.isCurrent(group, before)).isTrue();
        worker.tick();
        assertThat(orders.findById(high).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(orders.findById(low).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertCashConservation();
    }

    @Test
    void 구버전에서_선정된_후순위는_새호가로_자동체결하지않는다() {
        book("ASK", "100", 1, 1);
        long high = place("BUY", "1", "101");
        long low = place("BUY", "1", "100");
        LimitExecutionPreparation oldMarket = execution.prepare(stock, OrderSide.BUY);
        publication.publish(replacementBook(), NOW.plusSeconds(3600)).orElseThrow();
        assertThat(execution.execute(low, oldMarket).reason()).isEqualTo(LimitExecutionOutcome.Reason.PRIORITY_CHANGED);
        assertThat(number("SELECT count(*) FROM trade_execution WHERE order_id=?", low)).isZero();
        scopedWorker().tick();
        assertThat(orders.findById(high).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(orders.findById(low).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    private LimitOrderExecutionWorker scopedWorker() {
        // 다른 테스트의 종목은 제외하되 후보 페이지·체결·접수 이벤트는 실제 DB/서비스를 사용합니다.
        Group group = new Group(stock, OrderSide.BUY);
        LimitExecutionCandidateRepository scoped = new LimitExecutionCandidateRepository(jdbc) {
            @Override
            public Optional<Group> nextGroup(Group after, OffsetDateTime now) {
                return after == null ? Optional.of(group) : Optional.empty();
            }
        };
        return new LimitOrderExecutionWorker(scoped, execution, progress, new SimpleMeterRegistry(), clock,
                50, 1, 1, Duration.ofSeconds(5));
    }

    @Test
    void 비랭킹_첫주문은_호가를_기다렸다가_체결하고_수집대상에서_빠진다() {
        jdbc.update("UPDATE stock SET is_ranked=false WHERE stock_id=?",stock);
        long order = place("BUY","1","100");
        clearInvocations(rates);
        assertThat(execute(order).reason()).isEqualTo(LimitExecutionOutcome.Reason.NO_BOOK);
        verifyNoInteractions(rates);
        assertThat(stocks.isQuoteTarget(stock,NOW.atOffset(ZoneOffset.UTC))).isTrue();
        book("ASK","100",1,1);
        assertThat(execute(order).executionCount()).isEqualTo(1);
        assertThat(stocks.isQuoteTarget(stock,NOW.atOffset(ZoneOffset.UTC))).isFalse();
    }

    @Test
    void 과거호가가_삭제되어도_다른환율의_추가매도를_누적정산한다() {
        jdbc.update("INSERT INTO holding(account_id,stock_id,quantity,locked_quantity,avg_buy_price,avg_exchange_rate,usd_purchase_amount,krw_purchase_amount,updated_at) VALUES (?,?,3,0,90,1400,270,378000,?)",account,stock,NOW.atOffset(ZoneOffset.UTC));
        book("BID","100",1,1);
        long order = place("SELL","2","100");
        assertThat(execute(order).executionCount()).isEqualTo(1);
        Long historicalLevel = jdbc.queryForObject("SELECT book_level_id FROM trade_execution WHERE order_id=?",Long.class,order);
        jdbc.update("UPDATE order_book_version SET is_active=false,closed_at=? WHERE book_version_id=?",NOW.atOffset(ZoneOffset.UTC),version);
        jdbc.update("DELETE FROM order_book_version WHERE book_version_id=?",version);
        assertThat(number("SELECT count(*) FROM order_book_level WHERE level_id=?",historicalLevel)).isZero();
        book("BID","100",1,1);
        rate("1300");
        assertThat(execute(order).executionCount()).isEqualTo(1);
        assertThat(number("SELECT tax FROM trade_order WHERE order_id=?",order)).isEqualByComparingTo("14");
        assertThat(number("SELECT net_amount FROM trade_order WHERE order_id=?",order)).isEqualByComparingTo("269959");
        assertThat(number("SELECT count(*) FROM ledger_entry WHERE order_id=?",order)).isEqualByComparingTo("2");
        assertThat(orders.findById(order).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void 계산가능한_0주_미리보기는_AVAILABLE이고_DB는_변경하지않는다() {
        book("ASK","101",1,1);
        com.baedang.trading.dto.LimitExecutionPreviewResponse preview = admission.quote(user,symbol,"US","BUY","1","100","USD").executionPreview();
        assertThat(preview.status()).isEqualTo(com.baedang.trading.dto.LimitExecutionPreviewResponse.Status.AVAILABLE);
        assertThat(preview.reason()).isEqualTo("PRICE_LIMIT");
        assertThat(preview.expectedFilledQuantity()).isEqualTo("0");
        assertThat(preview.avgExecutionPrice()).isNull();
        assertThat(number("SELECT count(*) FROM trade_order WHERE account_id=?",account)).isZero();
        assertThat(number("SELECT revision FROM order_book_version WHERE book_version_id=?",version)).isZero();
    }
    private LimitExecutionAttempt attempt(long id) {
        com.baedang.trading.entity.TradeOrder order = orders.findById(id).orElseThrow();
        LimitExecutionBook book = books.read(stocks.findById(stock).orElseThrow(),order.getSide(),NOW).orElseThrow();
        return new LimitExecutionAttempt(order.getAccountId(),id,stock,order.getExecutionCount(),book.version(),book.revision(),
                new OrderMarketContext(MarketCountry.US,true,NOW.plusSeconds(3600),ExecutionRateEvidence.from(rates.currentUsdKrwSnapshot()),NOW));
    }
    private void book(String side,String first,int firstQuantity,int secondQuantity) {
        version = jdbc.queryForObject("INSERT INTO order_book_version(stock_id,base_price,currency,quote_at,generated_at,policy_version,seed) VALUES (?,100,'USD',?,?,'test',1) RETURNING book_version_id",Long.class,stock,NOW.atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC));
        for (int depth=1;depth<=10;depth++) {
            BigDecimal price = new BigDecimal(first).add(BigDecimal.valueOf((side.equals("ASK") ? 1 : -1) * (depth-1)));
            int quantity = depth==1 ? firstQuantity : depth==2 ? secondQuantity : 5;
            jdbc.update("INSERT INTO order_book_level(book_version_id,side,level_depth,price,initial_quantity,remaining_quantity) VALUES (?,?,?,?,?,?)",version,side,depth,price,quantity,quantity);
        }
    }
    private BigDecimal number(String sql,Object...args) { return jdbc.queryForObject(sql,BigDecimal.class,args); }

    private void assertCashConservation() {
        assertThat(number("SELECT cash_balance FROM account WHERE account_id=?",account))
                .isEqualByComparingTo(number("SELECT sum(amount) FROM ledger_entry WHERE account_id=?",account));
        assertThat(number("SELECT locked_cash FROM account WHERE account_id=?",account))
                .isEqualByComparingTo(number("SELECT coalesce(sum(reserved_cash),0) FROM trade_order WHERE account_id=? AND side='BUY' AND status IN ('PENDING','PARTIALLY_FILLED')",account));
    }

    @Test
    void 실제_DB락타임아웃은_한번_재시도후_보류하고_커넥션에_설정이_남지않는다() throws Exception {
        book("ASK","100",3,1);
        long order = place("BUY","1","100");
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> blocker = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT account_id FROM account WHERE account_id=? FOR UPDATE",Long.class,account);
                locked.countDown();
                try { if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                Future<LimitExecutionOutcome> fill = pool.submit(() -> execute(order));
                assertThat(fill.get(10,TimeUnit.SECONDS).reason()).isEqualTo(LimitExecutionOutcome.Reason.LOCK_BUSY);
            } finally { release.countDown(); }
            blocker.get(5,TimeUnit.SECONDS);
        }
        assertThat(number("SELECT count(*) FROM trade_execution WHERE order_id=?",order)).isZero();
        assertThat(jdbc.queryForObject("SHOW lock_timeout",String.class)).isEqualTo("0");
        assertCashConservation();
    }
}
