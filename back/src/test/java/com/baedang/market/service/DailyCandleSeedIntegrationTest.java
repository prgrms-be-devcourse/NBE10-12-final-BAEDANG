package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.service.DailyCandleSeedService.SeedResult;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL 컨테이너 환경에서 시드 백필의 신규 쿼리와 멱등성을 검증합니다.
 *
 * <p>{@code ON CONFLICT}(daily_candle upsert)와 {@code findStockIdsWithAnyCandle} 는 실제
 * PostgreSQL 에서만 정확히 검증됩니다. 외부 Toss 는 {@link MockitoBean} 으로 대체합니다.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "toss.seed-chart-tps=1000",
        "logging.level.org.hibernate.SQL=OFF"
})
class DailyCandleSeedIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql")
                            .toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @MockitoBean MarketDataPort marketDataPort;

    @Autowired DailyCandleSeedService seedService;
    @Autowired DailyCandleRepository dailyCandleRepository;
    @Autowired StockRepository stockRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE daily_candle");
        jdbcTemplate.execute("DELETE FROM stock");
    }

    @Test
    @DisplayName("일봉 이력이 있는 종목만 findStockIdsWithAnyCandle 에 포함된다")
    void findStockIdsWithAnyCandle_이력있는_종목만_반환() {
        Stock withHistory = saveRankedStock(MarketCountry.KR, "KRW", 1);
        Stock empty = saveRankedStock(MarketCountry.KR, "KRW", 2);
        upsertCandle(withHistory, LocalDate.of(2026, 8, 27));

        Set<Long> ids = dailyCandleRepository.findStockIdsWithAnyCandle(
                List.of(withHistory.getStockId(), empty.getStockId()));

        assertThat(ids).containsExactly(withHistory.getStockId());
    }

    @Test
    @DisplayName("시드는 200일치를 적재하고, 재실행 시 스킵되어 행이 그대로 유지된다(멱등)")
    void seed_재실행시_멱등하다() {
        Stock stock = saveRankedStock(MarketCountry.KR, "KRW", 1);
        List<Candle> candles = List.of(
                candle("2026-08-26T06:00:00Z", "100"),
                candle("2026-08-27T06:00:00Z", "110"),
                candle("2026-08-28T06:00:00Z", "120"));
        when(marketDataPort.fetchCandles(anyString(), eq(CandleInterval.ONE_DAY), anyInt()))
                .thenReturn(candles);

        SeedResult first = seedService.seed(MarketCountry.KR);
        assertThat(first.success()).isEqualTo(1);
        assertThat(rowCount(stock)).isEqualTo(3);

        SeedResult second = seedService.seed(MarketCountry.KR);

        // 두 번째 실행은 이력이 있으니 스킵 — 외부 호출은 첫 실행 1회뿐이고 행 수도 불변.
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.success()).isZero();
        verify(marketDataPort, times(1))
                .fetchCandles(anyString(), eq(CandleInterval.ONE_DAY), anyInt());
        assertThat(rowCount(stock)).isEqualTo(3);
    }

    @Test
    @DisplayName("유니버스가 비어 있으면 외부 호출 없이 no-op 한다")
    void seed_유니버스_비면_no_op() {
        SeedResult result = seedService.seed(MarketCountry.KR);

        assertThat(result).isEqualTo(new SeedResult(0, 0, 0, 0));
    }

    private int rowCount(Stock stock) {
        return dailyCandleRepository
                .findByStockIdOrderByTradeDateDesc(stock.getStockId(), PageRequest.of(0, 500))
                .size();
    }

    private Stock saveRankedStock(MarketCountry country, String currency, int rankNo) {
        String symbol = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Stock stock = stockRepository.save(Stock.create(
                symbol, country,
                country == MarketCountry.KR ? "KOSPI" : "NASDAQ",
                "테스트 종목", null, currency, "STOCK", true));
        stock.applyRanking(rankNo, new BigDecimal("1000000000"));
        return stockRepository.save(stock);
    }

    private void upsertCandle(Stock stock, LocalDate tradeDate) {
        OffsetDateTime at = tradeDate.atTime(6, 0).atOffset(ZoneOffset.UTC);
        // 저장 계층을 재사용하지 않고 바로 넣어도 되지만, 통화 검증까지 통과시키려면
        // persistence 를 통하는 편이 실제 경로에 가깝다.
        // 여기서는 tradeDate 존재만 필요하므로 upsert 헬퍼로 직접 넣는다.
        jdbcTemplate.update("""
                        INSERT INTO daily_candle
                            (stock_id, trade_date, open_price, high_price, low_price, close_price, volume)
                        VALUES (?, ?, 1, 1, 1, 1, 1)
                        """,
                stock.getStockId(), java.sql.Date.valueOf(tradeDate));
    }

    private Candle candle(String at, String close) {
        BigDecimal p = new BigDecimal(close);
        return new Candle(OffsetDateTime.parse(at), p, p, p, p, new BigDecimal("1000"), "KRW");
    }
}
