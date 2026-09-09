package com.baedang.market.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.QuoteSnapshotBatchRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.TradeOrder;
import com.baedang.trading.repository.TradeOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuoteSnapshotBatchRepository.class, QuoteSnapshotPersistenceService.class,
        com.baedang.global.config.JpaConfig.class})
class QuoteCollectionIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18").asCompatibleSubstituteFor("postgres"));
    @Autowired StockRepository stocks;
    @Autowired TradeOrderRepository orders;
    @Autowired QuoteSnapshotPersistenceService persistence;
    @Autowired JdbcTemplate jdbc;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-09T01:00:00Z");

    @Test
    void 역순응답과_같은시각_늦은응답에서도_최신가격과_기타컬럼을_보존한다() {
        Stock stock = stock(false);
        persistence.saveOrUpdate(List.of(stock), List.of(price(stock, "100", NOW)), NOW);
        jdbc.update("UPDATE quote_snapshot SET prev_close=90, upper_limit=130, lower_limit=70 WHERE stock_id=?", stock.getStockId());
        assertThat(persistence.saveOrUpdate(List.of(stock), List.of(price(stock, "80", NOW.minusSeconds(1))), NOW.plusSeconds(1))).isZero();
        assertThat(persistence.saveOrUpdate(List.of(stock), List.of(price(stock, "101", NOW)), NOW.plusSeconds(2))).isEqualTo(1);
        assertThat(persistence.saveOrUpdate(List.of(stock), List.of(price(stock, "99", NOW)), NOW.plusSeconds(1))).isZero();
        assertThat(jdbc.queryForObject("SELECT last_price FROM quote_snapshot WHERE stock_id=?", BigDecimal.class, stock.getStockId())).isEqualByComparingTo("101");
        assertThat(jdbc.queryForObject("SELECT prev_close FROM quote_snapshot WHERE stock_id=?", BigDecimal.class, stock.getStockId())).isEqualByComparingTo("90");
        assertThat(jdbc.queryForObject("SELECT upper_limit FROM quote_snapshot WHERE stock_id=?", BigDecimal.class, stock.getStockId())).isEqualByComparingTo("130");
        assertThat(jdbc.queryForObject("SELECT lower_limit FROM quote_snapshot WHERE stock_id=?", BigDecimal.class, stock.getStockId())).isEqualByComparingTo("70");
        assertThat(jdbc.queryForObject("SELECT quote_at FROM quote_snapshot WHERE stock_id=?", OffsetDateTime.class, stock.getStockId()).toInstant()).isEqualTo(NOW.toInstant());
        persistence.updatePrevClose(stock.getStockId(), new BigDecimal("95"));
        assertThat(jdbc.queryForObject("SELECT last_price FROM quote_snapshot WHERE stock_id=?", BigDecimal.class, stock.getStockId())).isEqualByComparingTo("101");
    }

    @Test
    void 활성주문이_중복돼도_대상은_한번이고_마지막종료후_수집에서_제외된다() {
        Stock ranked = stock(true);
        Stock requested = stock(false);
        Stock idle = stock(false);
        TradeOrder first = order(requested, NOW.plusMinutes(5));
        TradeOrder second = order(requested, NOW.plusMinutes(5));
        order(idle, NOW.minusSeconds(1));
        assertThat(targets()).extracting(Stock::getStockId).containsExactly(ranked.getStockId(), requested.getStockId());
        first.cancel(NOW);
        orders.flush();
        assertThat(targets()).extracting(Stock::getStockId).contains(requested.getStockId());
        second.cancel(NOW);
        orders.flush();
        assertThat(targets()).extracting(Stock::getStockId).containsExactly(ranked.getStockId());
        assertThat(stocks.findQuoteTargets(MarketCountry.KR, requested.getStockId(), NOW, PageRequest.of(0, 200)))
                .isEmpty();
        assertThat(stocks.findQuoteTargets(MarketCountry.US, 0L, NOW, PageRequest.of(0, 200))).isEmpty();
    }

    private List<Stock> targets() {
        return stocks.findQuoteTargets(MarketCountry.KR, 0L, NOW, PageRequest.of(0, 200));
    }

    private Stock stock(boolean ranked) {
        Stock stock = Stock.create(UUID.randomUUID().toString().substring(0, 8), MarketCountry.KR,
                "KOSPI", "test", null, "KRW", "STOCK", true);
        if (ranked) stock.applyRanking(1, BigDecimal.TEN);
        return stocks.saveAndFlush(stock);
    }

    private TradeOrder order(Stock stock, OffsetDateTime expires) {
        String key = UUID.randomUUID().toString();
        Long user = jdbc.queryForObject("INSERT INTO users(email,password_hash,nickname) VALUES (?,'x',?) RETURNING user_id",
                Long.class, key + "@test.com", key.substring(0, 16));
        Long account = jdbc.queryForObject("INSERT INTO account(user_id,initial_cash,cash_balance) VALUES (?,10000,10000) RETURNING account_id", Long.class, user);
        return orders.saveAndFlush(TradeOrder.pendingLimitOrder(account, stock.getStockId(), UUID.randomUUID(),
                OrderSide.BUY, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                NOW.minusMinutes(10), expires, new BigDecimal("100"), "KRW", BigDecimal.ONE));
    }

    private PriceQuote price(Stock stock, String value, OffsetDateTime at) {
        return new PriceQuote(stock.getSymbol(), new BigDecimal(value), at, "KRW");
    }
}
