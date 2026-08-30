package com.baedang.market.service;

import com.baedang.market.port.Candle;
import com.baedang.market.repository.DailyCandleRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 컨테이너 환경에서 일봉 배치 UPSERT 및 KST 날짜 변환 검증.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "logging.level.org.hibernate.SQL=OFF"
})
class DailyCandleCollectionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql")
                            .toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @Autowired DailyCandlePersistenceService persistenceService;
    @Autowired DailyCandleRepository dailyCandleRepository;
    @Autowired StockRepository stockRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE daily_candle");
    }

    @Test
    @DisplayName("동일 (stock_id, trade_date) 재삽입 시 최신값으로 갱신된다")
    void UPSERT_동일날짜_재삽입시_최신값으로_갱신된다() {
        Stock stock = saveStock(MarketCountry.KR, "KRW");
        OffsetDateTime candleAt = OffsetDateTime.of(2026, 8, 28, 6, 0, 0, 0, ZoneOffset.UTC);

        persistenceService.upsert(stock.getStockId(), stock.getMarketCountry(), "KRW",
                List.of(candle(candleAt, "100", "KRW")));
        persistenceService.upsert(stock.getStockId(), stock.getMarketCountry(), "KRW",
                List.of(candle(candleAt, "110", "KRW")));

        var rows = dailyCandleRepository.findByStockIdOrderByTradeDateDesc(
                stock.getStockId(), PageRequest.of(0, 10));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getClosePrice()).isEqualByComparingTo("110");
    }

    @Test
    @DisplayName("여러 날짜의 캔들을 한 번에 배치 저장한다")
    void 여러날짜_캔들_배치_저장된다() {
        Stock stock = saveStock(MarketCountry.KR, "KRW");

        persistenceService.upsert(stock.getStockId(), stock.getMarketCountry(), "KRW", List.of(
                candle(OffsetDateTime.of(2026, 8, 26, 6, 0, 0, 0, ZoneOffset.UTC), "100", "KRW"),
                candle(OffsetDateTime.of(2026, 8, 27, 6, 0, 0, 0, ZoneOffset.UTC), "110", "KRW"),
                candle(OffsetDateTime.of(2026, 8, 28, 6, 0, 0, 0, ZoneOffset.UTC), "120", "KRW")));

        var rows = dailyCandleRepository.findByStockIdOrderByTradeDateDesc(
                stock.getStockId(), PageRequest.of(0, 10));
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.getClosePrice().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("100", "110", "120");
    }

    @Test
    @DisplayName("미국 종목은 뉴욕 현지 거래일 기준으로 변환되어 저장된다")
    void 미국종목_뉴욕_현지_거래일자_기준_저장() {
        Stock stock = saveStock(MarketCountry.US, "USD");
        // UTC 2026-08-27 20:00:00 = 뉴욕 현지 2026-08-27 16:00:00 (KST 8/28 05:00)
        // 현지 거래일자인 2026-08-27로 저장되어야 함
        OffsetDateTime usCloseUtc = OffsetDateTime.of(2026, 8, 27, 20, 0, 0, 0, ZoneOffset.UTC);

        persistenceService.upsert(stock.getStockId(), stock.getMarketCountry(), "USD",
                List.of(candle(usCloseUtc, "150", "USD")));

        var rows = dailyCandleRepository.findByStockIdOrderByTradeDateDesc(
                stock.getStockId(), PageRequest.of(0, 10));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTradeDate()).isEqualTo(LocalDate.of(2026, 8, 27));
    }

    private Stock saveStock(MarketCountry country, String currency) {
        String symbol = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return stockRepository.save(Stock.create(
                symbol, country,
                country == MarketCountry.KR ? "KOSPI" : "NASDAQ",
                "테스트 종목", null, currency, "STOCK", true));
    }

    private Candle candle(OffsetDateTime at, String close, String currency) {
        BigDecimal p = new BigDecimal(close);
        return new Candle(at, p, p, p, p, new BigDecimal("1000"), currency);
    }
}
