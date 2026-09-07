package com.baedang.orderbook.service;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
    void 재기동_시점에도_DB에_저장된_소비_잔량과_revision이_유지되고_새_갱신_주기에서_교체된다() {
        scheduler.refreshOrderBooks();
        Long v1 = activeVersion().orElseThrow().getBookVersionId();

        // 소비 트랜잭션: ASK 1 수량 5 감소 및 revision 1 증가
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OrderBookVersion version = versionRepository.findById(v1).orElseThrow();
            OrderBookLevel ask1 = levelRepository.findAskLevelsForUpdate(v1).getFirst();
            ask1.consume(new BigDecimal("5"));
            version.advanceRevision();
        });

        // 새 요청(재기동 후 첫 조회와 동일)에서 잔량 감소와 revision 1이 그대로 조회된다
        var response = queryService.getOrderBook(krStock.getSymbol(), "KR");
        assertThat(response.bookVersion()).isEqualTo(v1);
        assertThat(response.revision()).isEqualTo(1L);

        // 다음 스케줄 갱신 주기에서 refresh가 실행되면 이전 버전은 종료되고 새 버전(새 initial 공급량)이 발행된다
        clock.advance(Duration.ofSeconds(3));
        scheduler.refreshOrderBooks();

        var refreshed = queryService.getOrderBook(krStock.getSymbol(), "KR");
        assertThat(refreshed.bookVersion()).isNotEqualTo(v1);
        assertThat(refreshed.revision()).isZero();
    }

    @Test
    void trading_orderbook_enabled_플래그가_false이면_스케줄러_빈이_등록되지_않는다() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(OrderBookRefreshScheduler.class)
                .withPropertyValues("trading.orderbook.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OrderBookRefreshScheduler.class));
    }
}
