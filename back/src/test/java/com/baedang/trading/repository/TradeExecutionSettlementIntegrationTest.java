package com.baedang.trading.repository;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.TradeExecution;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.LedgerEntry;
import com.baedang.trading.model.CumulativeSettlementState;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.service.LimitOrderSettlementCalculator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TradeExecutionSettlementIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-04T01:00:00Z");
    @Autowired TradeExecutionRepository executions;
    @Autowired TradeOrderRepository orders;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerEntryRepository ledgers;

    @Test
    void 체결이_없으면_null이_아닌_영합계를_반환한다() {
        assertState(executions.summarizeByOrderId(-1L), CumulativeSettlementState.empty());
    }

    @ParameterizedTest
    @CsvSource({"US,SELL", "US,BUY", "KR,SELL", "KR,BUY"})
    void DB_재조회한_체결합계로_다음_정산을_동일하게_재개한다(MarketCountry country, OrderSide side) {
        var calculator = calculator();
        var order = seed(country, side);
        var state = CumulativeSettlementState.empty();
        for (int i = 1; i <= 2; i++) {
            BigDecimal price = new BigDecimal(i == 1 ? "100" : "900");
            BigDecimal rate = country == MarketCountry.KR ? BigDecimal.ONE
                    : new BigDecimal(i == 1 ? "1300.123456" : "1400.654321");
            var result = calculator.calculate(country, side, price, BigDecimal.ONE, rate, state);
            var at = AT.plusMinutes(i);
            var evidence = country == MarketCountry.KR ? ExecutionRateEvidence.krw(at)
                    : new ExecutionRateEvidence(rate, at, at, at.plusMinutes(1));
            Long bookLevelId = insertBookLevel(order, price, i);
            var execution = executions.save(TradeExecution.limit(order, country, UUID.randomUUID(), i, BigDecimal.ONE,
                    price, evidence, result.requireExecutionAmounts(), at, at, bookLevelId));
            var reserve = side == OrderSide.BUY
                    ? calculator.reserveAfterBuy(order.getReservedCash(), order.activeRemainingQuantity(), BigDecimal.ONE, result.netAmountKrw()).reservedCashAfter()
                    : BigDecimal.ZERO;
            order.applyExecution(execution, reserve);
            state = result.nextState();
        }
        entityManager.flush();
        entityManager.clear();
        // 실행 이력 이외의 인메모리 상태 없이 새 계산기와 DB 합계로 재개합니다.
        var restored = executions.summarizeByOrderId(order.getOrderId());
        assertState(restored, state);
        BigDecimal nextPrice = new BigDecimal("950");
        BigDecimal nextRate = new BigDecimal("1500.123456");
        var expected = calculator.calculate(country, side, nextPrice, BigDecimal.ONE, nextRate, state);
        var resumed = calculator().calculate(country, side, nextPrice, BigDecimal.ONE, nextRate, restored);
        assertThat(resumed).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(expected);
        var reloaded = orders.findById(order.getOrderId()).orElseThrow();
        assertThat(reloaded.getGrossAmount()).isEqualByComparingTo(restored.grossAmountKrw());
        assertThat(reloaded.getTax()).isEqualByComparingTo(restored.taxKrw());
        if (side == OrderSide.BUY) {
            var before = reloaded.getReservedCash();
            assertThat(reloaded.cancel(AT.plusMinutes(3)).releasedCash()).isEqualByComparingTo(before);
            assertThat(reloaded.getFilledQuantity()).isEqualByComparingTo("2");
            entityManager.flush();
            assertState(executions.summarizeByOrderId(order.getOrderId()), restored);
        }
        assertState(executions.summarizeByOrderId(seed(country, side).getOrderId()), CumulativeSettlementState.empty());
    }

    @ParameterizedTest
    @CsvSource({"CANCELED,0", "CANCELED,1", "EXPIRED,0", "EXPIRED,1"})
    void 지정가_종료상태와_잔여동결_및_기존체결_원장연결을_DB에_보존한다(OrderStatus target, int filled) {
        // 모델과 매핑만 검증합니다. 계좌 정산/취소 유스케이스를 대신 구현하지 않습니다.
        TradeOrder order = seed(MarketCountry.KR, OrderSide.BUY);
        Long executionId = null;
        Long bookLevelId = null;
        if (filled == 1) {
            var result = calculator().calculate(MarketCountry.KR, OrderSide.BUY,
                    new BigDecimal("90"), BigDecimal.ONE, null, CumulativeSettlementState.empty());
            bookLevelId = insertBookLevel(order, new BigDecimal("90"), 1);
            var execution = executions.save(TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1,
                    BigDecimal.ONE, new BigDecimal("90"), ExecutionRateEvidence.krw(AT),
                    result.requireExecutionAmounts(), AT, AT.plusSeconds(1), bookLevelId));
            executionId = execution.getExecutionId();
            var reservation = calculator().reserveAfterBuy(order.getReservedCash(), order.activeRemainingQuantity(),
                    BigDecimal.ONE, result.netAmountKrw());
            order.applyExecution(execution, reservation.reservedCashAfter());
            ledgers.save(LedgerEntry.execution(order, execution, new BigDecimal("49999910"), "매핑 검증"));
        }
        entityManager.flush();
        entityManager.clear();
        TradeOrder reloaded = orders.findById(order.getOrderId()).orElseThrow();
        assertThat(reloaded.getReservedCash()).isEqualByComparingTo(filled == 1 ? "2910" : "3000");
        assertThat(reloaded.getStatus()).isEqualTo(filled == 1 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.PENDING);
        var closure = target == OrderStatus.CANCELED ? reloaded.cancel(AT.plusSeconds(2)) : reloaded.expire(AT.plusHours(6));
        assertThat(closure.releasedCash()).isEqualByComparingTo(filled == 1 ? "2910" : "3000");
        entityManager.flush();
        entityManager.clear();
        TradeOrder closed = orders.findById(order.getOrderId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(target);
        assertThat(closed.getReservedCash()).isZero();
        assertThat(closed.getFilledQuantity()).isEqualByComparingTo(BigDecimal.valueOf(filled));
        assertThat(closed.getExecutionCount()).isEqualTo(filled);
        if (executionId != null) {
            var execution = executions.findById(executionId).orElseThrow();
            assertThat(execution.getBookLevelId()).isEqualTo(bookLevelId);
            assertThat(closed.getLastExecutedAt()).isEqualTo(AT.plusSeconds(1));
            var ledger = ledgers.findFirstByOrderIdOrderByEntryIdAsc(closed.getOrderId()).orElseThrow();
            assertThat(ledger.getExecutionId()).isEqualTo(executionId);
            assertThat(ledger.getAmount()).isEqualByComparingTo("-90");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void 활성_매수주문의_동결액을_0으로_저장하면_DB_제약이_거절한다(int filled) {
        TradeOrder order = seed(MarketCountry.KR, OrderSide.BUY);
        if (filled == 1) {
            var result = calculator().calculate(MarketCountry.KR, OrderSide.BUY,
                    new BigDecimal("90"), BigDecimal.ONE, null, CumulativeSettlementState.empty());
            Long bookLevelId = insertBookLevel(order, new BigDecimal("90"), 1);
            var execution = executions.save(TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1,
                    BigDecimal.ONE, new BigDecimal("90"), ExecutionRateEvidence.krw(AT),
                    result.requireExecutionAmounts(), AT, AT.plusSeconds(1), bookLevelId));
            order.applyExecution(execution, new BigDecimal("2910"));
            entityManager.flush();
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE trade_order SET reserved_cash = 0 WHERE order_id = ?", order.getOrderId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_order_limit_terms");
    }

    private TradeOrder seed(MarketCountry country, OrderSide side) {
        String key = UUID.randomUUID().toString();
        Long user = jdbc.queryForObject("INSERT INTO users(email, password_hash, nickname) VALUES (?, 'x', ?) RETURNING user_id",
                Long.class, key + "@test.com", key.substring(0, 16));
        Long account = jdbc.queryForObject("INSERT INTO account(user_id, initial_cash, cash_balance) VALUES (?, 50000000, 50000000) RETURNING account_id",
                Long.class, user);
        Long stock = jdbc.queryForObject("""
                INSERT INTO stock(symbol, market_country, market, name, currency, security_type)
                VALUES (?, ?, ?, 'test', ?, 'STOCK') RETURNING stock_id
                """, Long.class, key.substring(0, 8), country.name(), country == MarketCountry.KR ? "KOSPI" : "NASDAQ", country.defaultCurrency());
        return orders.saveAndFlush(TradeOrder.pendingLimitOrder(account, stock, UUID.randomUUID(), side,
                new BigDecimal("3"), new BigDecimal(side == OrderSide.BUY ? "1000" : "1"),
                side == OrderSide.BUY ? calculator().initialReservedCash(country, new BigDecimal("1000"),
                        new BigDecimal("3"), new BigDecimal("1500")) : BigDecimal.ZERO, AT, AT.plusHours(6),
                new BigDecimal(side == OrderSide.BUY ? "1000" : "1"), country.defaultCurrency(),
                country == MarketCountry.KR ? BigDecimal.ONE : new BigDecimal("1500")));
    }

    /** LIMIT 체결 fixture도 운영 FK와 동일하게 실제 호가 레벨을 참조한다. */
    private Long insertBookLevel(TradeOrder order, BigDecimal price, int depth) {
        Long versionId = jdbc.query("""
                        SELECT book_version_id
                          FROM order_book_version
                         WHERE stock_id = ? AND is_active = true
                        """,
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                order.getStockId());
        if (versionId == null) {
            versionId = jdbc.queryForObject("""
                    INSERT INTO order_book_version
                        (stock_id, base_price, currency, quote_at, generated_at, policy_version, seed, revision)
                    SELECT stock_id, ?, currency, ?, ?, 'V1', 1, 0
                      FROM stock
                     WHERE stock_id = ?
                    RETURNING book_version_id
                    """, Long.class, price, AT, AT, order.getStockId());
        }
        String side = order.getSide() == OrderSide.BUY ? "ASK" : "BID";
        return jdbc.queryForObject("""
                INSERT INTO order_book_level
                    (book_version_id, side, level_depth, price, initial_quantity, remaining_quantity)
                VALUES (?, ?, ?, ?, 10, 10)
                RETURNING level_id
                """, Long.class, versionId, side, depth, price);
    }

    private LimitOrderSettlementCalculator calculator() {
        return new LimitOrderSettlementCalculator(new BigDecimal("0.0001"), new BigDecimal("0.002"),
                new BigDecimal("0.0000206"), new BigDecimal("0.01"), new BigDecimal("1000000"));
    }

    private void assertState(CumulativeSettlementState actual, CumulativeSettlementState expected) {
        assertThat(actual).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(expected);
    }
}
