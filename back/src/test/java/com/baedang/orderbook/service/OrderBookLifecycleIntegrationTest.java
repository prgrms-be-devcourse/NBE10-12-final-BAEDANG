package com.baedang.orderbook.service;
import com.baedang.TradingApplication;
import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.StockDescriptor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.orderbook.entity.OrderBookVersion;
import com.baedang.orderbook.entity.OrderBookLevel;
import com.baedang.orderbook.repository.OrderBookLevelRepository;
import com.baedang.orderbook.repository.OrderBookVersionRepository;
import com.baedang.orderbook.scheduler.OrderBookRefreshScheduler;
import com.baedang.orderbook.support.MutableClock;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "trading.orderbook.enabled=true",
        "trading.orderbook.refresh-initial-delay=1h",
        "trading.orderbook.retention-initial-delay=1h",
        "logging.level.org.hibernate.SQL=OFF"
})
class OrderBookLifecycleIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-03T01:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql")
                            .toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @TestConfiguration
    static class ClockTestConfig {
        final MutableClock clock = new MutableClock(BASE);

        @Bean
        @Primary
        MutableClock mutableClock() {
            return clock;
        }
    }

    @MockitoBean MarketSessionProvider marketSessionProvider;
    @MockitoBean ExecutionExchangeRateProvider exchangeRateProvider;
    @MockitoBean MarketCalendarPort marketCalendarPort;

    @Autowired StockRepository stockRepository;
    @Autowired QuoteSnapshotRepository quoteSnapshotRepository;
    @Autowired OrderBookVersionRepository versionRepository;
    @Autowired OrderBookLevelRepository levelRepository;
    @Autowired OrderBookRefreshScheduler scheduler;
    @Autowired OrderBookQueryService queryService;
    @Autowired ClockTestConfig clockConfig;
    @Autowired PlatformTransactionManager transactionManager;

    private MutableClock clock;
    private Stock krStock;

    @BeforeEach
    void setUp() {
        clock = clockConfig.clock;
        clock.setCurrent(BASE);

        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(new MarketSessionStatus(true, BASE.plusSeconds(3600)));
        when(exchangeRateProvider.currentUsdKrwRate()).thenReturn(new BigDecimal("1383.60"));

        krStock = stockRepository.save(tradableStock());
        saveQuote(krStock.getStockId(), new BigDecimal("70000"), "KRW", BASE.minusSeconds(2));
    }

    private static Stock tradableStock() {
        Stock stock = Stock.create(
                "L" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(),
                MarketCountry.KR, "KOSPI", "생명주기 테스트 종목", null, "KRW", "STOCK", true);
        stock.applyRanking(1, new BigDecimal("1000000"));
        return stock;
    }

    private void saveQuote(Long stockId, BigDecimal price, String currency, Instant quoteAt) {
        var at = quoteAt.atOffset(ZoneOffset.UTC);
        quoteSnapshotRepository.save(new QuoteSnapshot(stockId, price, currency, at, at));
    }

    private Optional<OrderBookVersion> activeVersion() {
        return versionRepository.findByStockIdAndIsActiveTrue(krStock.getStockId());
    }

    @Test
    void 같은_quoteAt을_15초_내에서_재사용해도_새_version을_만든다() {
        scheduler.refreshOrderBooks();
        OrderBookVersion first = activeVersion().orElseThrow();
        Long firstVersionId = first.getBookVersionId();
        Instant originalQuoteAt = first.getQuoteAt().toInstant();

        // 3초 경과 -> 실제 현재가는 같고 quoteAt도 동일하지만 3초마다 새 버전으로 공급한다
        clock.advance(Duration.ofSeconds(3));
        scheduler.refreshOrderBooks();

        OrderBookVersion second = activeVersion().orElseThrow();
        assertThat(second.getBookVersionId()).isNotEqualTo(firstVersionId);
        assertThat(second.getQuoteAt().toInstant()).isEqualTo(originalQuoteAt);

        // 이전 버전은 종료되어 있음
        OrderBookVersion closedOld = versionRepository.findById(firstVersionId).orElseThrow();
        assertThat(closedOld.isActive()).isFalse();
        assertThat(closedOld.getClosedAt()).isNotNull();
    }

    @Test
    void 미래_quote는_활성버전을_종료하고_새버전을_만들지_않는다() {
        scheduler.refreshOrderBooks();
        assertThat(activeVersion()).isPresent();

        // 시세 시각이 미래로 업데이트된 경우
        saveQuote(krStock.getStockId(), new BigDecimal("70000"), "KRW", clock.instant().plusSeconds(5));

        scheduler.refreshOrderBooks();

        assertThat(activeVersion()).isEmpty();
    }

    @Test
    void stale_quote는_활성버전을_종료하고_새버전을_만들지_않는다() {
        scheduler.refreshOrderBooks();
        assertThat(activeVersion()).isPresent();

        // 시세 시각이 16초 전(> 15s maxQuoteAge)으로 지연된 경우
        saveQuote(krStock.getStockId(), new BigDecimal("70000"), "KRW", clock.instant().minusSeconds(16));

        scheduler.refreshOrderBooks();

        assertThat(activeVersion()).isEmpty();
    }

    @Test
    void 장외이거나_세션_종료시_활성버전을_종료하고_새버전을_만들지_않는다() {
        scheduler.refreshOrderBooks();
        assertThat(activeVersion()).isPresent();

        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(MarketSessionStatus.closed());

        scheduler.refreshOrderBooks();

        assertThat(activeVersion()).isEmpty();
    }

    @Test
    void 랭킹에서_이탈한_종목은_열린_시장에서도_활성버전을_종료한다() {
        scheduler.refreshOrderBooks();
        assertThat(activeVersion()).isPresent();

        // 랭킹 이탈
        krStock.clearRanking();
        stockRepository.save(krStock);

        scheduler.refreshOrderBooks();

        assertThat(activeVersion()).isEmpty();
    }

    @Test
    void quote가_누락되거나_통화가_불일치하면_활성버전을_종료한다() {
        scheduler.refreshOrderBooks();
        assertThat(activeVersion()).isPresent();

        // 통화 불일치 (KRW 종목에 USD 시세)
        saveQuote(krStock.getStockId(), new BigDecimal("70000"), "USD", clock.instant().minusSeconds(2));

        scheduler.refreshOrderBooks();

        assertThat(activeVersion()).isEmpty();
    }

    @Test
    void 저가_종목처럼_BID_10개를_양수로_만들_수_없으면_활성버전을_종료한다() {
        scheduler.refreshOrderBooks();
        assertThat(activeVersion()).isPresent();

        // 5원 가격은 1원 단위에서 10개 양수 BID(1~4까지 4개만 가능)를 만들 수 없음
        saveQuote(krStock.getStockId(), new BigDecimal("5"), "KRW", clock.instant().minusSeconds(2));

        scheduler.refreshOrderBooks();

        assertThat(activeVersion()).isEmpty();
    }

    @Test
    void deleteExpiredUnconsumedVersions_스케줄_실행으로_미소비_종료버전을_정리한다() {
        scheduler.refreshOrderBooks();
        Long v1 = activeVersion().orElseThrow().getBookVersionId();

        // v1 소비 (revision > 0)
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                versionRepository.findById(v1).orElseThrow().advanceRevision());

        // 다음 주기 -> v2 활성, v1 종료
        clock.advance(Duration.ofSeconds(3));
        scheduler.refreshOrderBooks();
        Long v2 = activeVersion().orElseThrow().getBookVersionId();

        // 다음 주기 -> v3 활성, v2 종료 (v2는 revision 0)
        clock.advance(Duration.ofSeconds(3));
        scheduler.refreshOrderBooks();
        Long v3 = activeVersion().orElseThrow().getBookVersionId();

        // 2분 경과 후 retention 스케줄 실행
        clock.advance(Duration.ofMinutes(2));
        scheduler.deleteExpiredUnconsumedVersions();

        // v1: revision > 0 -> 보존
        assertThat(versionRepository.findById(v1)).isPresent();
        // v2: revision 0 + retention 초과 -> 정리됨
        assertThat(versionRepository.findById(v2)).isEmpty();
        // v3: 활성 버전 -> 보존
        assertThat(versionRepository.findById(v3)).isPresent();
    }

    @Test
    void 재기동_후에도_별도_Spring_Context가_저장된_잔량과_revision을_읽고_다음_갱신에서_교체한다() {
        String symbol;
        Long firstVersion;
        BigDecimal remainingAfterConsumption;

        try (ConfigurableApplicationContext contextA = restartContext()) {
            StockRepository stocks = contextA.getBean(StockRepository.class);
            QuoteSnapshotRepository quotes = contextA.getBean(QuoteSnapshotRepository.class);
            OrderBookGenerator generator = contextA.getBean(OrderBookGenerator.class);
            OrderBookProperties properties = contextA.getBean(OrderBookProperties.class);
            OrderBookPublicationService publisher = contextA.getBean(OrderBookPublicationService.class);
            OrderBookVersionRepository versions = contextA.getBean(OrderBookVersionRepository.class);
            OrderBookLevelRepository levels = contextA.getBean(OrderBookLevelRepository.class);
            PlatformTransactionManager txManager = contextA.getBean(PlatformTransactionManager.class);

            Stock stock = stocks.saveAndFlush(tradableStock());
            symbol = stock.getSymbol();
            var at = BASE.minusSeconds(2).atOffset(ZoneOffset.UTC);
            quotes.saveAndFlush(new QuoteSnapshot(stock.getStockId(), new BigDecimal("70000"), "KRW", at, at));

            StockDescriptor descriptor = StockDescriptor.from(stock);
            GeneratedOrderBook generated = generator.generate(
                    properties, descriptor, new BigDecimal("70000"), BASE.minusSeconds(2), BASE, 41L);
            firstVersion = publisher.publish(generated, BASE.plusSeconds(3600)).orElseThrow();

            AtomicReference<BigDecimal> remaining = new AtomicReference<>();
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                OrderBookVersion version = versions.findById(firstVersion).orElseThrow();
                OrderBookLevel ask = levels.findAskLevelsForUpdate(firstVersion).getFirst();
                ask.consume(new BigDecimal("5"));
                version.advanceRevision();
                remaining.set(ask.getRemainingQuantity());
            });
            remainingAfterConsumption = remaining.get();
        }

        try (ConfigurableApplicationContext contextB = restartContext()) {
            OrderBookQueryService query = contextB.getBean(OrderBookQueryService.class);
            OrderBookRefreshScheduler restartedScheduler = contextB.getBean(OrderBookRefreshScheduler.class);

            var persisted = query.getOrderBook(symbol, "KR");
            assertThat(persisted.bookVersion()).isEqualTo(firstVersion);
            assertThat(persisted.revision()).isEqualTo(1L);
            assertThat(new BigDecimal(persisted.asks().getFirst().quantity()))
                    .isEqualByComparingTo(remainingAfterConsumption);

            restartedScheduler.refreshOrderBooks();

            var refreshed = query.getOrderBook(symbol, "KR");
            assertThat(refreshed.bookVersion()).isNotEqualTo(firstVersion);
            assertThat(refreshed.revision()).isZero();
        }
    }

    private ConfigurableApplicationContext restartContext() {
        return new SpringApplicationBuilder(TradingApplication.class, OrderBookRestartTestConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.sql.init.mode=never",
                        "toss.enabled=false",
                        "trading.orderbook.enabled=true",
                        "trading.orderbook.refresh-initial-delay=1h",
                        "trading.orderbook.retention-initial-delay=1h",
                        "server.port=0",
                        "JWT_SECRET=ZGV2LXNlY3JldC1rZXktZm9yLXRlc3Rpbmctb25seQ=="
                )
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--toss.enabled=false",
                        "--trading.orderbook.enabled=true",
                        "--trading.orderbook.refresh-initial-delay=1h",
                        "--trading.orderbook.retention-initial-delay=1h"
                );
    }

    @Test
    void trading_orderbook_enabled_플래그가_false이면_스케줄러_빈이_등록되지_않는다() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(OrderBookRefreshScheduler.class)
                .withPropertyValues("trading.orderbook.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OrderBookRefreshScheduler.class));
    }
}

@TestConfiguration(proxyBeanMethods = false)
class OrderBookRestartTestConfiguration {

    private static final Instant BASE = Instant.parse("2026-09-03T01:00:00Z");

    @Bean
    @Primary
    MutableClock restartClock() {
        return new MutableClock(BASE);
    }

    @Bean
    @Primary
    MarketSessionProvider restartMarketSessionProvider() {
        MarketSessionProvider provider = mock(MarketSessionProvider.class);
        when(provider.currentSession(any(), any()))
                .thenReturn(new MarketSessionStatus(true, BASE.plusSeconds(3600)));
        return provider;
    }

    @Bean
    @Primary
    ExecutionExchangeRateProvider restartExchangeRateProvider() {
        ExecutionExchangeRateProvider provider = mock(ExecutionExchangeRateProvider.class);
        when(provider.currentUsdKrwRate()).thenReturn(new BigDecimal("1383.60"));
        return provider;
    }
    @Bean(name = "marketCalendarDelegate")
    MarketCalendarPort marketCalendarDelegate() {
        return mock(MarketCalendarPort.class);
    }
}
