package com.baedang.trading.repository;

import com.baedang.report.support.HoldingLotTracker;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.HoldingReplayEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TradeExecutionRepository#findHoldingReplayEvents} 가 개별 체결을 주문과 조인해
 * 방향을 붙이고 <b>체결 시각순</b>으로 반환하는지 실제 DB로 검증한다. 핵심은 삽입/ID 순서가
 * 아니라 {@code executed_at} 이 정렬을 지배한다는 점 — 지정가 접수 순서로 재생하던 버그를 막는다.
 */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class HoldingReplayEventQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired TradeExecutionRepository executions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 개별_체결을_방향과_함께_체결시각순으로_반환한다() {
        long account = seedAccount();
        long stock = seedStock();

        // 삽입 순서를 체결 시각과 일부러 어긋나게 넣어(뒤 체결을 먼저 삽입) executed_at 이 정렬을
        // 지배함을 검증한다: 08-01 매수 10 → 09-08 전량 매도 → 09-09 매수 5(가장 늦게 체결).
        insertFill(account, stock, OrderSide.BUY, 5, "2026-09-09T00:00:00Z");   // 가장 늦은 체결을 먼저 삽입
        insertFill(account, stock, OrderSide.BUY, 10, "2026-08-01T00:00:00Z");
        insertFill(account, stock, OrderSide.SELL, 10, "2026-09-08T00:00:00Z");

        List<HoldingReplayEvent> events = executions.findHoldingReplayEvents(account, List.of(stock));

        assertThat(events).extracting(HoldingReplayEvent::side)
                .containsExactly(OrderSide.BUY, OrderSide.SELL, OrderSide.BUY);
        assertThat(events).extracting(HoldingReplayEvent::executedAt)
                .containsExactly(
                        OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                        OffsetDateTime.parse("2026-09-08T00:00:00Z"),
                        OffsetDateTime.parse("2026-09-09T00:00:00Z"));
        assertThat(events).allSatisfy(e -> assertThat(e.stockId()).isEqualTo(stock));

        // 재생 결과: 전량 매도로 닫힌 뒤 09-09 체결이 현재 lot 을 연다.
        assertThat(HoldingLotTracker.currentLotStart(events))
                .isEqualTo(OffsetDateTime.parse("2026-09-09T00:00:00Z"));
    }

    @Test
    void 다른_계좌의_체결은_섞이지_않는다() {
        long mine = seedAccount();
        long other = seedAccount();
        long stock = seedStock();
        insertFill(mine, stock, OrderSide.BUY, 10, "2026-08-01T00:00:00Z");
        insertFill(other, stock, OrderSide.BUY, 7, "2026-08-02T00:00:00Z");

        List<HoldingReplayEvent> events = executions.findHoldingReplayEvents(mine, List.of(stock));

        assertThat(events).singleElement()
                .satisfies(e -> assertThat(e.quantity()).isEqualByComparingTo("10"));
    }

    /** MARKET·FILLED 주문 한 건과 그 확정 체결 한 건을 삽입한다(방향·체결시각만 의미 있음). */
    private void insertFill(long accountId, long stockId, OrderSide side, long qty, String executedAt) {
        Long orderId = jdbc.queryForObject("""
                INSERT INTO trade_order(account_id, stock_id, client_order_id, side, order_type,
                        quantity, status, filled_quantity, execution_count, reserved_cash, ordered_at, closed_at)
                VALUES (?, ?, ?, ?, 'MARKET', ?, 'FILLED', ?, 1, 0, ?::timestamptz, ?::timestamptz)
                RETURNING order_id
                """, Long.class, accountId, stockId, UUID.randomUUID(), side.name(), qty, qty, executedAt, executedAt);
        jdbc.update("""
                INSERT INTO trade_execution(order_id, execution_key, sequence_no, quantity, price,
                        exchange_rate, sec_fee_usd, gross_amount_krw, fee_krw, tax_krw, net_amount_krw,
                        quote_at, executed_at)
                VALUES (?, ?, 1, ?, 100, 1, 0, 100, 0, 0, 100, ?::timestamptz, ?::timestamptz)
                """, orderId, UUID.randomUUID(), qty, executedAt, executedAt);
    }

    private long seedAccount() {
        String key = UUID.randomUUID().toString();
        Long user = jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, nickname) VALUES (?, 'x', ?) RETURNING user_id",
                Long.class, key + "@test.com", key.substring(0, 16));
        return jdbc.queryForObject(
                "INSERT INTO account(user_id, initial_cash, cash_balance) VALUES (?, 50000000, 50000000) RETURNING account_id",
                Long.class, user);
    }

    private long seedStock() {
        String key = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO stock(symbol, market_country, market, name, currency, security_type)
                VALUES (?, 'KR', 'KOSPI', 'test', 'KRW', 'STOCK') RETURNING stock_id
                """, Long.class, key.substring(0, 8));
    }
}
