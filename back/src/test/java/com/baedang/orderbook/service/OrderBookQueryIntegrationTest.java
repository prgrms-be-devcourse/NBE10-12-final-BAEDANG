package com.baedang.orderbook.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.dto.OrderBookLevelResponse;
import com.baedang.orderbook.dto.OrderBookResponse;
import com.baedang.orderbook.entity.OrderBookLevel;
import com.baedang.orderbook.entity.OrderBookVersion;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.orderbook.repository.OrderBookLevelRepository;
import com.baedang.orderbook.scheduler.OrderBookRefreshScheduler;
import com.baedang.orderbook.repository.OrderBookVersionRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "trading.orderbook.enabled=true",
        "logging.level.org.hibernate.SQL=OFF"
})
class OrderBookQueryIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-03T01:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    @TestConfiguration
    static class ClockTestConfig {
        final MutableClock clock = new MutableClock(BASE);

        @Bean
        @Primary
        MutableClock mutableClock() {
            return clock;
        }
    }
    @MockitoBean OrderBookRefreshScheduler scheduler;

    @MockitoBean MarketSessionProvider marketSessionProvider;
    @MockitoBean ExecutionExchangeRateProvider exchangeRateProvider;
    @MockitoBean MarketCalendarPort marketCalendarPort;

    @Autowired OrderBookPublicationService publicationService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OrderBookGenerator generator;
    @Autowired OrderBookProperties properties;
    @Autowired StockRepository stockRepository;
    @Autowired OrderBookVersionRepository versionRepository;
    @Autowired OrderBookLevelRepository levelRepository;
    @Autowired OrderBookQueryService queryService;
    @Autowired ClockTestConfig clockConfig;

    private MutableClock clock;
    private Stock krStock;
    private StockDescriptor descriptor;

    @BeforeEach
    void setUp() {
        clock = clockConfig.clock;
        clock.setCurrent(BASE);

        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(new MarketSessionStatus(true, BASE.plusSeconds(3600)));
        when(exchangeRateProvider.currentUsdKrwRate()).thenReturn(new BigDecimal("1383.60"));

        krStock = stockRepository.save(tradableStock());
        descriptor = StockDescriptor.from(krStock);
    }

    private static Stock tradableStock() {
        Stock stock = Stock.create(
                "Q" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(),
                MarketCountry.KR, "KOSPI", "조회 테스트 종목", null, "KRW", "STOCK", true);
        stock.applyRanking(1, new BigDecimal("1000000"));
        return stock;
    }

    private GeneratedOrderBook generatedBook(long seed, Instant quoteAt) {
        Instant now = clock.instant();
        return generator.generate(properties, descriptor, new BigDecimal("70000"), quoteAt, now, seed);
    }

    @Test
    void 국내_활성_호가_20개를_단일_SQL_스냅샷으로_조회한다() {
        Long versionId = publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), BASE.plusSeconds(3600)).orElseThrow();

        OrderBookResponse response = queryService.getOrderBook(krStock.getSymbol(), "KR");

        assertThat(response.symbol()).isEqualTo(krStock.getSymbol());
        assertThat(response.marketCountry()).isEqualTo("KR");
        assertThat(response.bookVersion()).isEqualTo(versionId);
        assertThat(response.revision()).isZero();
        assertThat(response.basePrice()).isEqualTo("70000");
        assertThat(response.currency()).isEqualTo("KRW");
        assertThat(response.virtual()).isTrue();
        assertThat(response.description()).isEqualTo("현재가 기반 가상 호가·가상 잔량");

        assertThat(response.asks()).hasSize(10);
        assertThat(response.bids()).hasSize(10);

        // ASK는 1..10 가격 오름차순
        assertThat(response.asks()).extracting(OrderBookLevelResponse::level)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (int i = 0; i < 9; i++) {
            BigDecimal current = new BigDecimal(response.asks().get(i).price());
            BigDecimal next = new BigDecimal(response.asks().get(i + 1).price());
            assertThat(current).isLessThan(next);
        }

        // BID는 1..10 가격 내림차순
        assertThat(response.bids()).extracting(OrderBookLevelResponse::level)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (int i = 0; i < 9; i++) {
            BigDecimal current = new BigDecimal(response.bids().get(i).price());
            BigDecimal next = new BigDecimal(response.bids().get(i + 1).price());
            assertThat(current).isGreaterThan(next);
        }

        // 최우선 매수(BID 1) < 최우선 매도(ASK 1)
        assertThat(new BigDecimal(response.bids().getFirst().price()))
                .isLessThan(new BigDecimal(response.asks().getFirst().price()));
    }

    @Test
    void 미국_저가_호가는_가능한_BID_깊이만_조회한다() {
        Stock usStock = stockRepository.save(Stock.create(
                "U" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(),
                MarketCountry.US, "NASDAQ", "저가 조회 테스트 종목", null, "USD", "STOCK", true));
        usStock.applyRanking(1, new BigDecimal("1000000"));
        stockRepository.save(usStock);
        StockDescriptor usDescriptor = StockDescriptor.from(usStock);
        GeneratedOrderBook generated = generator.generate(
                properties, usDescriptor, new BigDecimal("0.10"),
                BASE.minusSeconds(2), BASE, 42L);
        publicationService.publish(generated, BASE.plusSeconds(3600)).orElseThrow();

        OrderBookResponse response = queryService.getOrderBook(usStock.getSymbol(), "US");

        assertThat(response.asks()).hasSize(10);
        assertThat(response.bids()).hasSize(9);
        assertThat(response.bids()).extracting(OrderBookLevelResponse::level)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(response.bids().getLast().price()).isEqualTo("0.01");
    }

    @Test
    void 미국_일반가격_호가에서_BID_레벨이_누락되면_503이다() {
        Stock usStock = stockRepository.save(Stock.create(
                "U" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(),
                MarketCountry.US, "NASDAQ", "불완전 조회 테스트 종목", null, "USD", "STOCK", true));
        usStock.applyRanking(1, new BigDecimal("1000000"));
        stockRepository.save(usStock);
        GeneratedOrderBook generated = generator.generate(
                properties, StockDescriptor.from(usStock), new BigDecimal("100.00"),
                BASE.minusSeconds(2), BASE, 42L);
        Long versionId = publicationService.publish(generated, BASE.plusSeconds(3600)).orElseThrow();
        jdbcTemplate.update(
                "delete from order_book_level where book_version_id = ? and side = 'BID' and level_depth = 10",
                versionId);

        assertThatThrownBy(() -> queryService.getOrderBook(usStock.getSymbol(), "US"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_BOOK_UNAVAILABLE);
    }

    @Test
    void quoteAge_15초_이내는_허용하고_15초_초과는_503이다() {
        // 14초 전 시세로 게시된 호가는 현재 시점 기준 maxQuoteAge(15s) 이내이므로 정상 조회된다
        publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(14)), BASE.plusSeconds(3600)).orElseThrow();

        assertThat(queryService.getOrderBook(krStock.getSymbol(), "KR")).isNotNull();

        // 2초 경과 -> quoteAge가 16초가 됨 -> 스케줄러가 아직 닫지 않았더라도 GET 자체에서 503 거절한다
        clock.advance(Duration.ofSeconds(2));

        assertThatThrownBy(() -> queryService.getOrderBook(krStock.getSymbol(), "KR"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_BOOK_UNAVAILABLE);
    }

    @Test
    void 장외이거나_세션_종료시각에_도달하면_503이다() {
        Instant validUntil = BASE.plusSeconds(10);
        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(new MarketSessionStatus(true, validUntil));

        publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), validUntil).orElseThrow();
        clock.advance(Duration.ofSeconds(10));

        assertThatThrownBy(() -> queryService.getOrderBook(krStock.getSymbol(), "KR"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_BOOK_UNAVAILABLE);

        // 장이 닫힌 경우 503
        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(MarketSessionStatus.closed());

        assertThatThrownBy(() -> queryService.getOrderBook(krStock.getSymbol(), "KR"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_BOOK_UNAVAILABLE);
    }

    @Test
    void 기능_비활성_상태에서는_503이다() {
        publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), BASE.plusSeconds(3600)).orElseThrow();

        OrderBookProperties disabled = new OrderBookProperties(
                false, properties.policyVersion(), properties.refreshInterval(),
                properties.levelsPerSide(), properties.spreadStepsPerSide(), properties.maxQuoteAge(),
                properties.krBaseNotional(), properties.usBaseNotional(), properties.minQuantity(),
                properties.maxQuantity(), properties.noiseMinBps(), properties.noiseMaxBps(),
                properties.unconsumedRetention()
        );
        OrderBookQueryService disabledService = new OrderBookQueryService(
                stockRepository, levelRepository, marketSessionProvider, disabled, clock);

        assertThatThrownBy(() -> disabledService.getOrderBook(krStock.getSymbol(), "KR"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_BOOK_UNAVAILABLE);
    }

    @Test
    void 거래불가_종목은_503이다() {
        publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), BASE.plusSeconds(3600)).orElseThrow();

        krStock.updateFlags(true, false, false); // suspended
        stockRepository.save(krStock);

        assertThatThrownBy(() -> queryService.getOrderBook(krStock.getSymbol(), "KR"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_BOOK_UNAVAILABLE);
    }

    @Test
    void 잘못된_입력은_400_없는_종목은_404를_우선_반환한다() {
        // 잘못된 국가 코드는 기능 플래그나 종목 존재 여부와 무관하게 400
        assertThatThrownBy(() -> queryService.getOrderBook(krStock.getSymbol(), "INVALID"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 없는 심볼은 404
        assertThatThrownBy(() -> queryService.getOrderBook("NON_EXISTENT", "KR"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_NOT_FOUND);
    }

    @Test
    void 반복_조회해도_버전_seed_잔량은_변경되지_않는다() {
        Long versionId = publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), BASE.plusSeconds(3600)).orElseThrow();

        OrderBookResponse first = queryService.getOrderBook(krStock.getSymbol(), "KR");
        OrderBookResponse second = queryService.getOrderBook(krStock.getSymbol(), "KR");

        assertThat(second).isEqualTo(first);

        // DB 검증: 버전의 revision이나 레벨의 잔량이 전혀 변경되지 않음
        assertThat(versionRepository.findById(versionId).orElseThrow().getRevision()).isZero();
        assertThat(levelRepository.countByBookVersion_BookVersionId(versionId)).isEqualTo(20);
    }
    @Test
    void 미커밋_잔량과_revision_변경_중_reader는_이전_스냅샷을_읽고_commit_후_새_스냅샷을_읽는다() throws Exception {
        Long versionId = publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), BASE.plusSeconds(3600)).orElseThrow();
        OrderBookResponse initial = queryService.getOrderBook(krStock.getSymbol(), "KR");
        BigDecimal initialAskQuantity = new BigDecimal(initial.asks().getFirst().quantity());

        CountDownLatch writerReady = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        OrderBookVersion version = versionRepository.findById(versionId).orElseThrow();
                        OrderBookLevel ask = levelRepository.findAskLevelsForUpdate(versionId).getFirst();
                        ask.consume(BigDecimal.ONE);
                        version.advanceRevision();
                        versionRepository.flush();
                        writerReady.countDown();
                        try {
                            assertThat(releaseWriter.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                    }));

            assertThat(writerReady.await(5, TimeUnit.SECONDS)).isTrue();

            OrderBookResponse beforeCommit = queryService.getOrderBook(krStock.getSymbol(), "KR");
            assertThat(beforeCommit.bookVersion()).isEqualTo(versionId);
            assertThat(beforeCommit.revision()).isZero();
            assertThat(new BigDecimal(beforeCommit.asks().getFirst().quantity()))
                    .isEqualByComparingTo(initialAskQuantity);

            releaseWriter.countDown();
            writer.get(5, TimeUnit.SECONDS);

            OrderBookResponse afterCommit = queryService.getOrderBook(krStock.getSymbol(), "KR");
            assertThat(afterCommit.bookVersion()).isEqualTo(versionId);
            assertThat(afterCommit.revision()).isEqualTo(1L);
            assertThat(new BigDecimal(afterCommit.asks().getFirst().quantity()))
                    .isEqualByComparingTo(initialAskQuantity.subtract(BigDecimal.ONE));
        }
    }

    @Test
    void publication_close_insert_경계에서도_reader는_이전_또는_신규_전체_세트만_읽는다() throws Exception {
        Long previousVersion = publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), BASE.plusSeconds(3600)).orElseThrow();
        GeneratedOrderBook next = generatedBook(43L, BASE.minusSeconds(2));
        CountDownLatch writerReady = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        stockRepository.findByIdForUpdate(krStock.getStockId()).orElseThrow();
                        OrderBookVersion active = versionRepository.findActiveForUpdate(krStock.getStockId())
                                .orElseThrow();
                        active.close(clock.instant());
                        versionRepository.flush();
                        writerReady.countDown();
                        try {
                            assertThat(releaseWriter.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                        OrderBookVersion replacement = versionRepository.saveAndFlush(OrderBookVersion.open(next));
                        levelRepository.saveAll(OrderBookLevel.from(replacement, next.levels()));
                    }));

            assertThat(writerReady.await(5, TimeUnit.SECONDS)).isTrue();
            OrderBookResponse beforeCommit = queryService.getOrderBook(krStock.getSymbol(), "KR");
            assertThat(beforeCommit.bookVersion()).isEqualTo(previousVersion);
            assertThat(beforeCommit.asks()).hasSize(10);
            assertThat(beforeCommit.bids()).hasSize(10);

            releaseWriter.countDown();
            writer.get(5, TimeUnit.SECONDS);

            OrderBookResponse afterCommit = queryService.getOrderBook(krStock.getSymbol(), "KR");
            assertThat(afterCommit.bookVersion()).isNotEqualTo(previousVersion);
            assertThat(afterCommit.asks()).hasSize(10);
            assertThat(afterCommit.bids()).hasSize(10);
        }
    }

    @Test
    void future_quote_조회는_503이고_오류_GET은_DB_상태를_변경하지_않는다() {
        Long versionId = publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), BASE.plusSeconds(3600)).orElseThrow();
        jdbcTemplate.update(
                "update order_book_version set quote_at = ? where book_version_id = ?",
                BASE.plusSeconds(1).atOffset(ZoneOffset.UTC), versionId);
        OrderBookVersion beforeGet = versionRepository.findById(versionId).orElseThrow();
        long revision = beforeGet.getRevision();
        boolean active = beforeGet.isActive();
        var closedAt = beforeGet.getClosedAt();
        var remainingQuantities = levelRepository.findActiveSnapshotRows(krStock.getStockId()).stream()
                .map(row -> row.getRemainingQuantity())
                .toList();

        assertThatThrownBy(() -> queryService.getOrderBook(krStock.getSymbol(), "KR"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_BOOK_UNAVAILABLE);
        OrderBookVersion afterGet = versionRepository.findById(versionId).orElseThrow();
        assertThat(afterGet.getRevision()).isEqualTo(revision);
        assertThat(afterGet.isActive()).isEqualTo(active);
        assertThat(afterGet.getClosedAt()).isEqualTo(closedAt);
        assertThat(levelRepository.findActiveSnapshotRows(krStock.getStockId()))
                .extracting(row -> row.getRemainingQuantity())
                .containsExactlyElementsOf(remainingQuantities);

    }

    @Test
    void 통화가_불일치하는_활성_호가는_503이다() {
        Long versionId = publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), BASE.plusSeconds(3600)).orElseThrow();
        jdbcTemplate.update("update order_book_version set currency = 'USD' where book_version_id = ?", versionId);

        assertThatThrownBy(() -> queryService.getOrderBook(krStock.getSymbol(), "KR"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_BOOK_UNAVAILABLE);
    }

    @Test
    void 국내_호가에서_ASK_레벨이_누락되면_503이다() {
        Long versionId = publicationService.publish(
                generatedBook(42L, BASE.minusSeconds(2)), BASE.plusSeconds(3600)).orElseThrow();
        jdbcTemplate.update(
                "delete from order_book_level where book_version_id = ? and side = 'ASK' and level_depth = 10",
                versionId);

        assertThatThrownBy(() -> queryService.getOrderBook(krStock.getSymbol(), "KR"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_BOOK_UNAVAILABLE);
    }

}
