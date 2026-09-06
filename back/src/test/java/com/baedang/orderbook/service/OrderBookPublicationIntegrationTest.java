package com.baedang.orderbook.service;

import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.entity.OrderBookVersion;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.orderbook.repository.OrderBookLevelRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 버전 교체는 partial unique index가 최종 방어선인 원자적 트랜잭션이다.
 * 목 리포지토리로는 "실제 커밋된 활성 버전 수"와 "락 경합 후의 상태"를 검증할
 * 수 없어서 실제 PostgreSQL 18(Testcontainers) + infra/schema.sql 위에서 실행한다.
 *
 * <p>클래스를 {@code @Transactional}로 감싸지 않는 이유는 일부 테스트가 진짜
 * 커밋(스레드 간 경합)을 요구하기 때문이다. 대신 테스트마다 새 종목을 만들어
 * 상태를 격리한다.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "logging.level.org.hibernate.SQL=OFF"
})
class OrderBookPublicationIntegrationTest {

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

    @Autowired OrderBookPublicationService publicationService;
    @Autowired OrderBookRetentionService retentionService;
    @Autowired OrderBookGenerator generator;
    @Autowired OrderBookProperties properties;
    @Autowired StockRepository stockRepository;
    @Autowired OrderBookVersionRepository versionRepository;
    @Autowired OrderBookLevelRepository levelRepository;
    @Autowired ClockTestConfig clockConfig;
    @Autowired PlatformTransactionManager transactionManager;

    private MutableClock clock;
    private Stock krStock;
    private StockDescriptor descriptor;

    @BeforeEach
    void setUp() {
        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(new MarketSessionStatus(true, Instant.MAX));
        when(exchangeRateProvider.currentUsdKrwRate()).thenReturn(new BigDecimal("1383.60"));

        clock = clockConfig.clock;
        clock.setCurrent(BASE);
        krStock = stockRepository.save(tradableStock());
        descriptor = StockDescriptor.from(krStock);
    }

    private static Stock tradableStock() {
        Stock stock = Stock.create(
                "T" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(),
                MarketCountry.KR, "KOSPI", "호가 테스트 종목", null, "KRW", "STOCK", true);
        stock.applyRanking(1, new BigDecimal("1000000"));
        return stock;
    }

    /** 현재 시계 기준의 정상 생성 입력 (quoteAt은 2초 전 시세). */
    private GeneratedOrderBook generatedBook(long seed) {
        Instant now = clock.instant();
        return generator.generate(properties, descriptor, new BigDecimal("70000"), now.minusSeconds(2), now, seed);
    }

    @Test
    void 두_publisher가_경합해도_활성_버전은_하나다() throws Exception {
        GeneratedOrderBook first = generatedBook(41L);
        GeneratedOrderBook second = generatedBook(42L);
        Instant sessionEnd = BASE.plusSeconds(600);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<Long>> a = executor.submit(() -> {
                start.await();
                return publicationService.publish(first, sessionEnd);
            });
            Future<Optional<Long>> b = executor.submit(() -> {
                start.await();
                return publicationService.publish(second, sessionEnd);
            });
            start.countDown();

            assertThat(a.get(10, TimeUnit.SECONDS)).isPresent();
            assertThat(b.get(10, TimeUnit.SECONDS)).isPresent();
        }

        assertThat(versionRepository.countByStockIdAndIsActiveTrue(krStock.getStockId())).isEqualTo(1);
        OrderBookVersion active = versionRepository
                .findByStockIdAndIsActiveTrue(krStock.getStockId()).orElseThrow();
        assertThat(levelRepository.countByBookVersion_BookVersionId(active.getBookVersionId())).isEqualTo(20);
    }

    @Test
    void 버전_교체는_기존버전_종료와_새레벨_20개를_함께_커밋한다() {
        Instant sessionEnd = BASE.plusSeconds(600);
        Long first = publicationService.publish(generatedBook(41L), sessionEnd).orElseThrow();
        Long second = publicationService.publish(generatedBook(42L), sessionEnd).orElseThrow();

        OrderBookVersion closed = versionRepository.findById(first).orElseThrow();
        assertThat(closed.isActive()).isFalse();
        assertThat(closed.getClosedAt()).isNotNull();

        OrderBookVersion active = versionRepository.findById(second).orElseThrow();
        assertThat(active.isActive()).isTrue();
        assertThat(active.getClosedAt()).isNull();
        assertThat(levelRepository.countByBookVersion_BookVersionId(second)).isEqualTo(20);
    }

    @Test
    void 세션이_끝나면_기존만_종료하고_새버전을_만들지_않는다() {
        Instant sessionEnd = BASE.plusSeconds(5);
        Long first = publicationService.publish(generatedBook(41L), sessionEnd).orElseThrow();

        clock.advance(Duration.ofSeconds(6)); // validUntil 경과
        Optional<Long> result = publicationService.publish(generatedBook(42L), sessionEnd);

        assertThat(result).isEmpty();
        assertThat(versionRepository.findById(first).orElseThrow().isActive()).isFalse();
        assertThat(versionRepository.findByStockIdAndIsActiveTrue(krStock.getStockId())).isEmpty();
    }

    @Test
    void maxQuoteAge를_넘긴_stale_quote는_게시를_거절하고_활성을_종료한다() {
        Long first = publicationService.publish(
                generatedBook(41L), BASE.plusSeconds(600)).orElseThrow();

        // maxQuoteAge(15s)보다 오래된 시세로 새 버전을 만들려 하면 거절 + 기존 활성 종료.
        clock.advance(Duration.ofSeconds(20));
        GeneratedOrderBook stale = generator.generate(
                properties, descriptor, new BigDecimal("70000"),
                clock.instant().minusSeconds(16), clock.instant(), 42L);
        Optional<Long> rejected = publicationService.publish(stale, clock.instant().plusSeconds(600));

        assertThat(rejected).isEmpty();
        assertThat(versionRepository.findById(first).orElseThrow().isActive()).isFalse();
        assertThat(versionRepository.findByStockIdAndIsActiveTrue(krStock.getStockId())).isEmpty();
    }

    @Test
    void 미래_quoteAt은_게시를_거절한다() {
        publicationService.publish(generatedBook(41L), BASE.plusSeconds(600));

        Instant now = clock.instant();
        GeneratedOrderBook future = generator.generate(
                properties, descriptor, new BigDecimal("70000"),
                now.plusSeconds(5), now, 43L);
        Optional<Long> rejected = publicationService.publish(future, now.plusSeconds(600));

        assertThat(rejected).isEmpty();
        assertThat(versionRepository.findByStockIdAndIsActiveTrue(krStock.getStockId())).isEmpty();
    }

    @Test
    void closeActive는_새_버전_없이_활성만_종료한다() {
        Long published = publicationService.publish(
                generatedBook(41L), BASE.plusSeconds(600)).orElseThrow();

        publicationService.closeActive(krStock.getStockId());

        assertThat(versionRepository.findById(published).orElseThrow().isActive()).isFalse();
        assertThat(versionRepository.findByStockIdAndIsActiveTrue(krStock.getStockId())).isEmpty();
    }

    @Test
    void 거래불가_종목은_게시를_거절하고_기존_활성을_종료한다() {
        publicationService.publish(generatedBook(41L), BASE.plusSeconds(600));

        krStock.updateFlags(true, false, false); // 거래정지
        stockRepository.save(krStock);

        Optional<Long> result = publicationService.publish(
                generatedBook(42L), BASE.plusSeconds(600));

        assertThat(result).isEmpty();
        assertThat(versionRepository.findByStockIdAndIsActiveTrue(krStock.getStockId())).isEmpty();
    }

    @Test
    void 미소비_종료버전만_retention으로_삭제된다() {
        // v1: 소비(revision > 0) → 종료. v2: 미소비 종료 + retention 경과.
        // v3: 종료했지만 retention 미경과 → 보존.
        // v1을 활성 상태에서 소비(revision > 0)시키고 교체한다 — advanceRevision은
        // 활성 버전에서만 허용되므로 종료 전에 호출해야 한다.
        Long v1 = publicationService.publish(generatedBook(41L), BASE.plusSeconds(600)).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                versionRepository.findById(v1).orElseThrow().advanceRevision());
        Long v2 = publicationService.publish(generatedBook(42L), BASE.plusSeconds(600)).orElseThrow();
        Long v3 = publicationService.publish(generatedBook(43L), BASE.plusSeconds(600)).orElseThrow();

        clock.advance(Duration.ofMinutes(2));
        publicationService.closeActive(krStock.getStockId()); // v3 종료 시각 = 지금 → retention 미경과

        retentionService.deleteExpiredUnconsumed();

        // deleted 건수는 다른 테스트의 커밋 잔여(이 클래스는 롤백하지 않음)에 따라
        // 달라지므로, 특정 행의 보존/삭제 결과만 단언한다.
        assertThat(versionRepository.findById(v1)).isPresent(); // revision > 0 → 감사 근거 보존
        assertThat(versionRepository.findById(v2)).isEmpty();   // closed + revision 0 + retention 초과 → 삭제
        assertThat(versionRepository.findById(v3)).isPresent(); // closed_at이 cutoff 이후 → 아직 보존
    }
}
