package com.baedang.stock.service;

import com.baedang.market.entity.DailyCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.repository.MinuteCandleRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
class CandleQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql")
                            .toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @MockitoBean MarketDataPort marketDataPort;
    // 개발용 대역(Fake) 구현체가 없어졌으므로, 이 테스트가 관심 없는 MarketCalendarPort
    // 의존을 목(mock)으로 채워 넣어야 컨텍스트가 뜬다(다른 서비스가 직접 주입받는다).
    @MockitoBean MarketCalendarPort marketCalendarPort;

    @Autowired CandleQueryService candleQueryService;
    @Autowired MinuteCandlePersistenceService persistenceService;
    @Autowired StockRepository stockRepository;
    @Autowired DailyCandleRepository dailyCandleRepository;
    @Autowired MinuteCandleRepository minuteCandleRepository;

    @Test
    void 일봉은_최신_N개를_시간순으로_반환한다() {
        Stock stock = saveStock(MarketCountry.KR, "KRW");
        dailyCandleRepository.saveAll(List.of(
                daily(stock, LocalDate.of(2026, 8, 25), "100"),
                daily(stock, LocalDate.of(2026, 8, 26), "110"),
                daily(stock, LocalDate.of(2026, 8, 27), "120")));

        var response = candleQueryService.getCandles(
                stock.getSymbol(), "KR", "1d", "1M");

        assertThat(response.items()).extracting(item -> item.close())
                .containsExactly("100", "110", "120");
    }

    @Test
    void 분봉_외부조회는_트랜잭션밖에서_실행하고_중복키를_UPSERT한다() {
        Stock stock = saveStock(MarketCountry.US, "USD");
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
        Candle first = candle(at, "100", "USD");
        Candle corrected = candle(at, "105", "USD");
        AtomicBoolean externalCallInTransaction = new AtomicBoolean(true);
        when(marketDataPort.fetchCandles(stock.getSymbol(), CandleInterval.ONE_MINUTE, 200))
                .thenAnswer(invocation -> {
                    externalCallInTransaction.set(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return List.of(first);
                });

        var response = candleQueryService.getCandles(
                stock.getSymbol(), "US", "1m", "1D");
        persistenceService.upsert(stock.getStockId(), List.of(corrected));

        assertThat(externalCallInTransaction).isFalse();
        assertThat(response.items()).hasSize(1);
        assertThat(minuteCandleRepository.findTopByStockIdOrderByCandleAtDesc(stock.getStockId()))
                .get()
                .extracting(row -> row.getClosePrice())
                .satisfies(value -> assertThat((BigDecimal) value).isEqualByComparingTo("105"));
    }

    private Stock saveStock(MarketCountry country, String currency) {
        String symbol = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return stockRepository.save(Stock.create(
                symbol, country, country == MarketCountry.KR ? "KOSPI" : "NASDAQ",
                "테스트 종목", null, currency, "STOCK", true));
    }

    private DailyCandle daily(Stock stock, LocalDate date, String close) {
        BigDecimal price = new BigDecimal(close);
        return new DailyCandle(
                stock.getStockId(), date, price, price, price, price, new BigDecimal("1000"));
    }

    private Candle candle(OffsetDateTime at, String close, String currency) {
        BigDecimal price = new BigDecimal(close);
        return new Candle(at, price, price, price, price, new BigDecimal("1000"), currency);
    }
}
