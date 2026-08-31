package com.baedang.market.service;

import com.baedang.market.entity.DailyCandle;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.model.PrevCloseUpdateResult;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
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

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@Transactional
class PrevCloseUpdateIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql")
                            .toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @Autowired PrevCloseUpdateService service;
    @Autowired StockRepository stockRepository;
    @Autowired DailyCandleRepository dailyCandleRepository;
    @Autowired QuoteSnapshotRepository quoteSnapshotRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 최근_일봉과_폴백으로_해당_시장_상위종목만_갱신한다() {
        Stock latest = saveStock(MarketCountry.KR, true);
        Stock fallback = saveStock(MarketCountry.KR, true);
        Stock missingSnapshot = saveStock(MarketCountry.KR, true);
        Stock unranked = saveStock(MarketCountry.KR, false);
        Stock us = saveStock(MarketCountry.US, true);

        saveSnapshot(latest, "90", "10");
        saveSnapshot(fallback, "55", "11");
        saveSnapshot(unranked, "300", "12");
        saveSnapshot(us, "200", "13");

        dailyCandleRepository.saveAllAndFlush(List.of(
                candle(latest, "2026-08-27", "100"),
                candle(latest, "2026-08-28", "120"),
                candle(unranked, "2026-08-28", "310"),
                candle(us, "2026-08-28", "210")
        ));

        PrevCloseUpdateResult result = service.update(MarketCountry.KR);

        assertThat(result).isEqualTo(new PrevCloseUpdateResult(3, 2, 1));
        assertThat(prevClose(latest)).isEqualByComparingTo("120");
        assertThat(prevClose(fallback)).isEqualByComparingTo("55");
        assertThat(prevClose(unranked)).isEqualByComparingTo("12");
        assertThat(prevClose(us)).isEqualByComparingTo("13");
        assertThat(quoteSnapshotRepository.findById(missingSnapshot.getStockId())).isEmpty();
    }

    @Test
    void 동일_시장을_재실행해도_결과가_같다() {
        Stock stock = saveStock(MarketCountry.US, true);
        saveSnapshot(stock, "150", "140");
        dailyCandleRepository.saveAndFlush(candle(stock, "2026-08-28", "155"));

        service.update(MarketCountry.US);
        service.update(MarketCountry.US);

        assertThat(prevClose(stock)).isEqualByComparingTo("155");
    }

    private Stock saveStock(MarketCountry marketCountry, boolean ranked) {
        String symbol = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Stock stock = Stock.create(
                symbol,
                marketCountry,
                marketCountry == MarketCountry.KR ? "KOSPI" : "NASDAQ",
                "테스트 종목",
                null,
                marketCountry == MarketCountry.KR ? "KRW" : "USD",
                "STOCK",
                true
        );
        if (ranked) stock.applyRanking(1, BigDecimal.TEN);
        return stockRepository.saveAndFlush(stock);
    }

    private void saveSnapshot(Stock stock, String lastPrice, String prevClose) {
        OffsetDateTime at = OffsetDateTime.of(2026, 8, 28, 9, 0, 0, 0, ZoneOffset.ofHours(9));
        QuoteSnapshot snapshot = new QuoteSnapshot(
                stock.getStockId(),
                new BigDecimal(lastPrice),
                stock.getCurrency(),
                at,
                at
        );
        snapshot.updatePrevClose(new BigDecimal(prevClose));
        quoteSnapshotRepository.saveAndFlush(snapshot);
    }

    private DailyCandle candle(Stock stock, String tradeDate, String closePrice) {
        BigDecimal close = new BigDecimal(closePrice);
        return new DailyCandle(
                stock.getStockId(),
                LocalDate.parse(tradeDate),
                close,
                close,
                close,
                close,
                BigDecimal.TEN
        );
    }

    private BigDecimal prevClose(Stock stock) {
        return jdbcTemplate.queryForObject(
                "SELECT prev_close FROM quote_snapshot WHERE stock_id = ?",
                BigDecimal.class,
                stock.getStockId()
        );
    }
}
