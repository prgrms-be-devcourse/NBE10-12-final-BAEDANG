package com.baedang.market.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceLimits;
import com.baedang.market.repository.PriceLimitRepository;
import com.baedang.market.repository.QuoteSnapshotBatchRepository;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never", "toss.enabled=false"})
class PriceLimitPersistenceIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18").asCompatibleSubstituteFor("postgres"));
    @MockitoBean MarketCalendarPort calendar;
    @Autowired StockRepository stocks;
    @Autowired QuoteSnapshotRepository quotes;
    @Autowired QuoteSnapshotBatchRepository prices;
    @Autowired PriceLimitRepository limits;
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-11T09:00:00+09:00");
    private static final LocalDate DATE = AT.toLocalDate();

    private Stock stock(MarketCountry country) {
        return stocks.save(Stock.create(UUID.randomUUID().toString().substring(0, 6), country,
                country == MarketCountry.KR ? "KOSPI" : "NASDAQ", "테스트", null, country.defaultCurrency(), "STOCK", true));
    }
    private PriceLimits value() {
        return new PriceLimits(AT, new BigDecimal("130"), new BigDecimal("70"), "KRW");
    }
    private QuoteSnapshot quote(Stock stock, String price, OffsetDateTime at) {
        return new QuoteSnapshot(stock.getStockId(), new BigDecimal(price), stock.getCurrency(), at, at);
    }

    @Test
    void 날짜없는_기존값은_보존하고_신규적용일을_저장한다() {
        Stock stock = stock(MarketCountry.KR);
        QuoteSnapshot legacy = quote(stock, "100", AT);
        legacy.updateLimits(new BigDecimal("120"), new BigDecimal("80"));
        quotes.saveAndFlush(legacy);
        assertThat(quotes.findById(stock.getStockId()).orElseThrow().getPriceLimitDate()).isNull();
        assertThat(limits.save(stock.getStockId(), DATE, value())).isTrue();
        assertThat(limits.save(stock.getStockId(), DATE.minusDays(1), value())).isFalse();
        assertThat(limits.save(stock.getStockId(), DATE, value())).isFalse();
        QuoteSnapshot saved = quotes.findById(stock.getStockId()).orElseThrow();
        assertThat(saved.getPriceLimitDate()).isEqualTo(DATE);
        assertThat(saved.getUpperLimit()).isEqualByComparingTo("130");
        assertThat(saved.getLastPrice()).isEqualByComparingTo("100");
        assertThat(saved.getQuoteAt().toInstant()).isEqualTo(AT.toInstant());
    }

    @Test
    void 수집중_다른_요청이_먼저_저장하면_DB에_확정된_스냅샷을_반환한다() {
        Stock stock = stock(MarketCountry.KR);
        QuoteSnapshot before = quotes.saveAndFlush(quote(stock, "100", AT));
        MarketDataPort data = mock(MarketDataPort.class);
        MarketTradingDayPolicy days = mock(MarketTradingDayPolicy.class);
        Clock clock = Clock.fixed(AT.plusHours(1).toInstant(), ZoneOffset.UTC);
        when(days.calendar(MarketCountry.KR, DATE)).thenReturn(new MarketCalendarDay(
                MarketCountry.KR, DATE, true, AT, AT.withHour(15).withMinute(30), null));
        PriceLimits accepted = new PriceLimits(AT, new BigDecimal("135"), new BigDecimal("75"), "KRW");
        when(data.fetchPriceLimits(stock.getSymbol())).thenAnswer(invocation -> {
            // 외부 응답 대기 중 다른 트랜잭션이 먼저 커밋한 순서를 재현합니다.
            assertThat(limits.save(stock.getStockId(), DATE, accepted)).isTrue();
            return value();
        });
        PriceLimitLoadService service = new PriceLimitLoadService(data, days, quotes, limits, clock, true);

        QuoteSnapshot result = service.ensureForDisplay(stock, before);

        assertThat(result.getPriceLimitDate()).isEqualTo(DATE);
        assertThat(result.getUpperLimit()).isEqualByComparingTo("135");
        assertThat(result.getLowerLimit()).isEqualByComparingTo("75");
        assertThat(result.getLastPrice()).isEqualByComparingTo("100");
        assertThat(result.getQuoteAt().toInstant()).isEqualTo(AT.toInstant());
    }

    @Test
    void 현재가와_동시갱신해도_서로의_필드를_보존한다() throws Exception {
        Stock stock = stock(MarketCountry.KR);
        quotes.saveAndFlush(quote(stock, "100", AT));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> limits.save(stock.getStockId(), DATE, value()));
            Future<?> second = executor.submit(() -> prices.savePrices(
                    List.of(quote(stock, "110", AT.plusSeconds(5))), MarketCountry.KR));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        QuoteSnapshot saved = quotes.findById(stock.getStockId()).orElseThrow();
        assertThat(saved.getLastPrice()).isEqualByComparingTo("110");
        assertThat(saved.getUpperLimit()).isEqualByComparingTo("130");
        assertThat(saved.getPriceLimitDate()).isEqualTo(DATE);
    }

    @Test
    void 미국과_시세없는_종목은_저장하지_않는다() {
        Stock missing = stock(MarketCountry.KR);
        assertThat(limits.save(missing.getStockId(), DATE, value())).isFalse();
        assertThat(quotes.findById(missing.getStockId())).isEmpty();
        Stock us = stock(MarketCountry.US);
        quotes.saveAndFlush(quote(us, "100", AT));
        assertThat(limits.save(us.getStockId(), DATE, value())).isFalse();
        assertThat(quotes.findById(us.getStockId()).orElseThrow().getUpperLimit()).isNull();
    }
}
