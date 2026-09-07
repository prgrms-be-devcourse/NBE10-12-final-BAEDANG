package com.baedang.orderbook.service;

import com.baedang.market.port.ExecutionExchangeRateProvider;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.port.MarketSessionStatus;
import com.baedang.orderbook.config.OrderBookProperties;
import com.baedang.orderbook.entity.OrderBookLevel;
import com.baedang.orderbook.entity.OrderBookSide;
import com.baedang.orderbook.entity.OrderBookVersion;
import com.baedang.orderbook.model.GeneratedOrderBook;
import com.baedang.orderbook.model.LockedOrderBook;
import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.orderbook.port.OrderBookExecutionStore;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
class OrderBookExecutionStoreIntegrationTest {

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
    @Autowired OrderBookGenerator generator;
    @Autowired OrderBookProperties properties;
    @Autowired StockRepository stockRepository;
    @Autowired OrderBookVersionRepository versionRepository;
    @Autowired OrderBookLevelRepository levelRepository;
    @Autowired OrderBookExecutionStore store;
    @Autowired ClockTestConfig clockConfig;
    @Autowired PlatformTransactionManager transactionManager;

    private MutableClock clock;
    private Stock krStock;
    private StockDescriptor descriptor;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        clock = clockConfig.clock;
        clock.setCurrent(BASE);

        when(marketSessionProvider.currentSession(any(), any()))
                .thenReturn(new MarketSessionStatus(true, BASE.plusSeconds(3600)));
        when(exchangeRateProvider.currentUsdKrwRate()).thenReturn(new BigDecimal("1383.60"));

        krStock = stockRepository.save(tradableStock());
        descriptor = StockDescriptor.from(krStock);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private static Stock tradableStock() {
        Stock stock = Stock.create(
                "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(),
                MarketCountry.KR, "KOSPI", "잠금 테스트 종목", null, "KRW", "STOCK", true);
        stock.applyRanking(1, new BigDecimal("1000000"));
        return stock;
    }

    private GeneratedOrderBook generatedBook(long seed) {
        Instant now = clock.instant();
        return generator.generate(properties, descriptor, new BigDecimal("70000"), now.minusSeconds(2), now, seed);
    }

    @Test
    void 기대한_version과_revision이_맞으면_version과_레벨을_잠근다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();

        Optional<LockedOrderBook> locked = transactionTemplate.execute(status ->
                store.lockForExecution(krStock.getStockId(), bookVersion, 0L, OrderBookSide.ASK));

        assertThat(locked).isPresent();
        LockedOrderBook book = locked.orElseThrow();
        assertThat(book.version().getBookVersionId()).isEqualTo(bookVersion);
        assertThat(book.version().getRevision()).isZero();
        assertThat(book.levels()).hasSize(10);
        assertThat(book.levels()).allSatisfy(l -> assertThat(l.getSide()).isEqualTo(OrderBookSide.ASK));

        // ASK: price 오름차순, depth 1..10
        for (int i = 0; i < 9; i++) {
            assertThat(book.levels().get(i).getPrice())
                    .isLessThan(book.levels().get(i + 1).getPrice());
            assertThat(book.levels().get(i).getLevelDepth()).isEqualTo(i + 1);
        }
    }

    @Test
    void BID_방향은_가격_내림차순으로_잠근다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();

        Optional<LockedOrderBook> locked = transactionTemplate.execute(status ->
                store.lockForExecution(krStock.getStockId(), bookVersion, 0L, OrderBookSide.BID));

        assertThat(locked).isPresent();
        LockedOrderBook book = locked.orElseThrow();
        assertThat(book.levels()).hasSize(10);
        assertThat(book.levels()).allSatisfy(l -> assertThat(l.getSide()).isEqualTo(OrderBookSide.BID));

        // BID: price 내림차순, depth 1..10
        for (int i = 0; i < 9; i++) {
            assertThat(book.levels().get(i).getPrice())
                    .isGreaterThan(book.levels().get(i + 1).getPrice());
            assertThat(book.levels().get(i).getLevelDepth()).isEqualTo(i + 1);
        }
    }

    @Test
    void revision이_바뀌었으면_빈결과를_반환한다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();

        Optional<LockedOrderBook> locked = transactionTemplate.execute(status ->
                store.lockForExecution(krStock.getStockId(), bookVersion, 3L, OrderBookSide.ASK));

        assertThat(locked).isEmpty();
    }

    @Test
    void version이_다르면_빈결과를_반환한다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();

        Optional<LockedOrderBook> locked = transactionTemplate.execute(status ->
                store.lockForExecution(krStock.getStockId(), bookVersion + 999L, 0L, OrderBookSide.ASK));

        assertThat(locked).isEmpty();
    }

    @Test
    void 종료된_버전은_기대한_version_revision이_맞아도_빈결과를_반환한다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();
        publicationService.closeActive(krStock.getStockId());

        Optional<LockedOrderBook> locked = transactionTemplate.execute(status ->
                store.lockForExecution(krStock.getStockId(), bookVersion, 0L, OrderBookSide.ASK));

        assertThat(locked).isEmpty();
    }

    @Test
    void 트랜잭션_밖에서는_잠금_계약을_호출할_수_없다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();

        assertThatThrownBy(() ->
                store.lockForExecution(krStock.getStockId(), bookVersion, 0L, OrderBookSide.ASK))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 필수_인자가_null이면_예외를_던진다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                store.lockForExecution(null, bookVersion, 0L, OrderBookSide.ASK)))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                store.lockForExecution(krStock.getStockId(), null, 0L, OrderBookSide.ASK)))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                store.lockForExecution(krStock.getStockId(), bookVersion, null, OrderBookSide.ASK)))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                store.lockForExecution(krStock.getStockId(), bookVersion, 0L, null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 한_트랜잭션에서_여러_레벨을_바꿔도_revision은_한번만_증가한다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();

        transactionTemplate.executeWithoutResult(status -> {
            LockedOrderBook locked = store.lockForExecution(krStock.getStockId(), bookVersion, 0L, OrderBookSide.ASK)
                    .orElseThrow();
            locked.levels().get(0).consume(BigDecimal.ONE);
            locked.levels().get(1).consume(BigDecimal.ONE);
            locked.levels().get(2).consume(BigDecimal.ONE);
            locked.version().advanceRevision();
        });

        // 새로운 트랜잭션에서 재조회: revision은 1이어야 하고 각 레벨 잔량이 1 감소되어야 함
        transactionTemplate.executeWithoutResult(status -> {
            OrderBookVersion updated = versionRepository.findById(bookVersion).orElseThrow();
            assertThat(updated.getRevision()).isEqualTo(1L);

            List<OrderBookLevel> levels = levelRepository.findAskLevelsForUpdate(bookVersion);
            assertThat(levels.get(0).getRemainingQuantity())
                    .isEqualByComparingTo(levels.get(0).getInitialQuantity().subtract(BigDecimal.ONE));
            assertThat(levels.get(1).getRemainingQuantity())
                    .isEqualByComparingTo(levels.get(1).getInitialQuantity().subtract(BigDecimal.ONE));
            assertThat(levels.get(2).getRemainingQuantity())
                    .isEqualByComparingTo(levels.get(2).getInitialQuantity().subtract(BigDecimal.ONE));
        });
    }

    @Test
    void 소비_트랜잭션_예외_시_잔량과_revision이_모두_롤백된다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();
        BigDecimal initialFirstRemaining = levelRepository.findAll().stream()
                .filter(l -> l.getBookVersion().getBookVersionId().equals(bookVersion) && l.getLevelDepth() == 1)
                .findFirst().orElseThrow().getRemainingQuantity();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            LockedOrderBook locked = store.lockForExecution(krStock.getStockId(), bookVersion, 0L, OrderBookSide.ASK)
                    .orElseThrow();
            locked.levels().get(0).consume(BigDecimal.ONE);
            locked.version().advanceRevision();
            throw new RuntimeException("강제 롤백 유발");
        })).isInstanceOf(RuntimeException.class);

        // 롤백 확인: revision 0 유지, remainingQuantity 원래 값 유지
        transactionTemplate.executeWithoutResult(status -> {
            OrderBookVersion rolledBack = versionRepository.findById(bookVersion).orElseThrow();
            assertThat(rolledBack.getRevision()).isZero();

            OrderBookLevel level1 = levelRepository.findAskLevelsForUpdate(bookVersion).getFirst();
            assertThat(level1.getRemainingQuantity()).isEqualByComparingTo(initialFirstRemaining);
        });
    }

    @Test
    void 서로_다른_두_소비_트랜잭션_후_revision은_2_증가한다() {
        Long bookVersion = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();

        // 1차 소비 트랜잭션: revision 0 -> 1
        transactionTemplate.executeWithoutResult(status -> {
            LockedOrderBook locked = store.lockForExecution(krStock.getStockId(), bookVersion, 0L, OrderBookSide.ASK)
                    .orElseThrow();
            locked.levels().get(0).consume(BigDecimal.ONE);
            locked.version().advanceRevision();
        });

        // 2차 소비 트랜잭션: revision 1 -> 2
        transactionTemplate.executeWithoutResult(status -> {
            LockedOrderBook locked = store.lockForExecution(krStock.getStockId(), bookVersion, 1L, OrderBookSide.ASK)
                    .orElseThrow();
            locked.levels().get(0).consume(BigDecimal.ONE);
            locked.version().advanceRevision();
        });

        // 최종 상태 확인
        transactionTemplate.executeWithoutResult(status -> {
            OrderBookVersion updated = versionRepository.findById(bookVersion).orElseThrow();
            assertThat(updated.getRevision()).isEqualTo(2L);
        });
    }

    @Test
    void consumer가_버전_락을_보유하면_publisher는_대기_후_이전_버전을_종료하고_새_버전을_게시한다() throws Exception {
        Long v1 = publicationService.publish(generatedBook(41L), BASE.plusSeconds(3600)).orElseThrow();

        CountDownLatch consumerHoldingLock = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // Consumer thread: v1 락 획득 후 대기
            Future<Long> consumerFuture = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        LockedOrderBook locked = store.lockForExecution(krStock.getStockId(), v1, 0L, OrderBookSide.ASK)
                                .orElseThrow();
                        consumerHoldingLock.countDown();
                        try {
                            assertThat(releaseConsumer.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        locked.version().advanceRevision();
                        return locked.version().getRevision();
                    })
            );

            assertThat(consumerHoldingLock.await(5, TimeUnit.SECONDS)).isTrue();

            // Publisher thread: v1 락이 풀릴 때까지 대기하다가 v1 close + v2 publish 수행
            Future<Optional<Long>> publisherFuture = executor.submit(() ->
                    publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600))
            );

            // 잠시 후 consumer 락 해제
            Thread.sleep(100);
            releaseConsumer.countDown();

            assertThat(consumerFuture.get(5, TimeUnit.SECONDS)).isEqualTo(1L);
            Optional<Long> v2 = publisherFuture.get(5, TimeUnit.SECONDS);
            assertThat(v2).isPresent();
            assertThat(v2.orElseThrow()).isNotEqualTo(v1);

            // v1은 종료되었고 v2가 활성 상태임
            Order1VersionState(v1, v2.orElseThrow());
        }
    }

    private void Order1VersionState(Long v1, Long v2) {
        transactionTemplate.executeWithoutResult(status -> {
            OrderBookVersion closed = versionRepository.findById(v1).orElseThrow();
            assertThat(closed.isActive()).isFalse();
            assertThat(closed.getClosedAt()).isNotNull();

            OrderBookVersion active = versionRepository.findById(v2).orElseThrow();
            assertThat(active.isActive()).isTrue();
        });
    }

    @Test
    void publisher가_새_버전으로_교체한_후에는_이전_버전을_기대한_consumer는_빈결과를_받는다() {
        Long v1 = publicationService.publish(generatedBook(41L), BASE.plusSeconds(3600)).orElseThrow();
        Long v2 = publicationService.publish(generatedBook(42L), BASE.plusSeconds(3600)).orElseThrow();

        // consumer가 이전 버전 v1을 기대하고 락을 요청하면 이미 종료된 버전이므로 empty 반환
        transactionTemplate.executeWithoutResult(status -> {
            Optional<LockedOrderBook> locked = store.lockForExecution(krStock.getStockId(), v1, 0L, OrderBookSide.ASK);
            assertThat(locked).isEmpty();
        });

        // 신규 활성 버전 v2로는 정상 잠금 가능
        transactionTemplate.executeWithoutResult(status -> {
            Optional<LockedOrderBook> locked = store.lockForExecution(krStock.getStockId(), v2, 0L, OrderBookSide.ASK);
            assertThat(locked).isPresent();
        });
    }

    @Test
    void publisher가_버전_교체_중이면_대기하던_consumer는_교체_완료_후_빈결과를_받는다() throws Exception {
        Long v1 = publicationService.publish(generatedBook(41L), BASE.plusSeconds(3600)).orElseThrow();

        CountDownLatch publisherHoldingLock = new CountDownLatch(1);
        CountDownLatch releasePublisher = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // Publisher thread: v1 락을 잡고 v1을 종료한 뒤 대기
            Future<Long> publisherFuture = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        stockRepository.findByIdForUpdate(krStock.getStockId()).orElseThrow();
                        OrderBookVersion active = versionRepository.findActiveForUpdate(krStock.getStockId()).orElseThrow();
                        active.close(clock.instant());
                        versionRepository.flush();

                        publisherHoldingLock.countDown();
                        try {
                            assertThat(releasePublisher.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        OrderBookVersion v2 = versionRepository.saveAndFlush(
                                OrderBookVersion.open(generatedBook(42L)));
                        return v2.getBookVersionId();
                    })
            );

            assertThat(publisherHoldingLock.await(5, TimeUnit.SECONDS)).isTrue();

            // Consumer thread: 이전 활성 버전 v1을 기대하고 락 요청 -> publisher 락 때문에 대기
            Future<Optional<LockedOrderBook>> consumerFuture = executor.submit(() ->
                    transactionTemplate.execute(status ->
                            store.lockForExecution(krStock.getStockId(), v1, 0L, OrderBookSide.ASK))
            );

            // 잠시 후 publisher 커밋
            Thread.sleep(100);
            releasePublisher.countDown();

            Long v2Id = publisherFuture.get(5, TimeUnit.SECONDS);
            assertThat(v2Id).isNotNull();

            // publisher가 커밋하면서 v1이 종료되었으므로, 대기에서 풀린 consumer는 empty를 반환받아야 함
            Optional<LockedOrderBook> consumerResult = consumerFuture.get(5, TimeUnit.SECONDS);
            assertThat(consumerResult).isEmpty();
        }
    }
}
