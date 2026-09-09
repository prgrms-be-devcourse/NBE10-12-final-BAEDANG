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
import com.baedang.orderbook.scheduler.OrderBookRefreshScheduler;
import com.baedang.orderbook.support.MutableClock;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 버전 교체는 partial unique index가 최종 방어선인 원자적 트랜잭션이다.
 * 목 리포지토리로는 "실제 커밋된 활성 버전 수"와 "락 경합 후의 상태"를 검증할
 * 수 없어서 실제 PostgreSQL 18(Testcontainers) + Flyway V1→V3 마이그레이션 위에서 실행한다.
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

    @MockitoBean MarketSessionProvider marketSessionProvider;
    @MockitoBean ExecutionExchangeRateProvider exchangeRateProvider;
    @MockitoBean OrderBookRefreshScheduler scheduler;
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
    @Autowired JdbcTemplate jdbcTemplate;

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
    void retention을_지난_종료버전은_소비여부와_무관하게_삭제된다() {
        // v1: 소비(revision > 0) 후 종료. v2: 미소비 종료. v3: 종료했지만 retention 미경과.
        Long v1 = publicationService.publish(generatedBook(41L), BASE.plusSeconds(600)).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                versionRepository.findById(v1).orElseThrow().advanceRevision());
        Long v2 = publicationService.publish(generatedBook(42L), BASE.plusSeconds(600)).orElseThrow();
        Long v3 = publicationService.publish(generatedBook(43L), BASE.plusSeconds(600)).orElseThrow();

        clock.advance(Duration.ofMinutes(2));
        publicationService.closeActive(krStock.getStockId()); // v3 종료 시각 = 지금 → retention 미경과

        retentionService.deleteExpiredClosed();

        // deleted 건수는 다른 테스트의 커밋 잔여에 따라 달라질 수 있어 특정 행만 단언한다.
        assertThat(versionRepository.findById(v1)).isEmpty();
        assertThat(versionRepository.findById(v2)).isEmpty();
        assertThat(versionRepository.findById(v3)).isPresent();
    }

    @Test
    void 신규_레벨_저장에_실패하면_이전_활성_종료까지_전체_롤백된다() {
        Long initialVersionId = publicationService.publish(generatedBook(41L), BASE.plusSeconds(600)).orElseThrow();

        GeneratedOrderBook valid = generatedBook(42L);
        var invalidLevels = new java.util.ArrayList<>(valid.levels());
        // level_depth 1 중복을 추가하여 uq_order_book_level 유니크 제약 위반 유발
        invalidLevels.add(new com.baedang.orderbook.model.GeneratedOrderBookLevel(
                com.baedang.orderbook.entity.OrderBookSide.ASK, 1, new BigDecimal("70100"), BigDecimal.TEN));
        GeneratedOrderBook corrupt = new GeneratedOrderBook(
                valid.stockId(), valid.basePrice(), valid.currency(), valid.quoteAt(), valid.generatedAt(),
                valid.policyVersion(), valid.seed(), invalidLevels);

        assertThatThrownBy(() ->
                publicationService.publish(corrupt, BASE.plusSeconds(600)))
                .isInstanceOf(Exception.class);

        // 롤백 확인: 이전 활성 버전이 계속 활성 상태(isActive = true, closedAt = null)여야 한다
        OrderBookVersion rolledBack = versionRepository.findById(initialVersionId).orElseThrow();
        assertThat(rolledBack.isActive()).isTrue();
        assertThat(rolledBack.getClosedAt()).isNull();
        assertThat(versionRepository.findByStockIdAndIsActiveTrue(krStock.getStockId()))
                .map(OrderBookVersion::getBookVersionId)
                .contains(initialVersionId);
    }

    @Test
    void 체결의_bookLevelId는_호가_삭제후에도_추적값으로_남는다() {
        Long v1 = publicationService.publish(generatedBook(41L), BASE.plusSeconds(600)).orElseThrow();
        Long levelId = levelRepository.findAll().stream()
                .filter(l -> l.getBookVersion().getBookVersionId().equals(v1))
                .findFirst().orElseThrow().getLevelId();
        publicationService.closeActive(krStock.getStockId());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long userId = jdbcTemplate.queryForObject(
                "insert into users (email, password_hash, nickname) values (?, 'pwd', ?) returning user_id",
                Long.class, "retention-" + suffix + "@example.com", "user-" + suffix);
        long accountId = jdbcTemplate.queryForObject(
                "insert into account (user_id, round_no, initial_cash, cash_balance, locked_cash, status, opened_at) values (?, 1, 1000000, 1000000, 0, 'ACTIVE', now()) returning account_id",
                Long.class, userId);
        long orderId = jdbcTemplate.queryForObject(
                """
                insert into trade_order (account_id, stock_id, client_order_id, order_type, side,
                                         quantity, filled_quantity, limit_price, reserved_cash,
                                         requested_limit_price, requested_limit_currency, acceptance_exchange_rate,
                                         status, ordered_at, closed_at, expires_at)
                values (?, ?, gen_random_uuid(), 'LIMIT', 'BUY', 1, 1, 70100, 0,
                        70100, 'KRW', 1, 'FILLED', now(), now(), now() + interval '1 hour')
                returning order_id
                """, Long.class, accountId, krStock.getStockId());
        jdbcTemplate.update(
                """
                insert into trade_execution (order_id, sequence_no, execution_key, quantity, price,
                                             exchange_rate, sec_fee_usd, gross_amount_krw, fee_krw, tax_krw,
                                             net_amount_krw, quote_at, executed_at, book_level_id)
                values (?, 1, gen_random_uuid(), 1, 70100, 1.0, 0, 70100, 0, 0, 70100, now(), now(), ?)
                """, orderId, levelId);

        clock.advance(Duration.ofMinutes(2));
        retentionService.deleteExpiredClosed();

        assertThat(versionRepository.findById(v1)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select book_level_id from trade_execution where order_id = ?", Long.class, orderId))
                .isEqualTo(levelId);
    }

    @Test
    void stock_락이_장시간_점유되면_publish는_대기를_중단하고_다음_호출에서_재시도한다() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> blocker = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        stockRepository.findByIdForUpdate(krStock.getStockId()).orElseThrow();
                        lockHeld.countDown();
                        try {
                            releaseLock.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                    }));
            assertThat(lockHeld.await(3, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> publicationService.publish(
                    generatedBook(42L), BASE.plusSeconds(600)))
                    .isInstanceOf(PessimisticLockingFailureException.class);

            releaseLock.countDown();
            blocker.get(3, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
        }

        assertThat(publicationService.publish(generatedBook(43L), BASE.plusSeconds(600))).isPresent();
    }

    @Test
    void 종료버전_락이_장시간_점유되면_retention은_대기를_중단하고_다음_호출에서_재시도한다() throws Exception {
        Long versionId = publicationService.publish(generatedBook(41L), BASE.plusSeconds(600)).orElseThrow();
        publicationService.closeActive(krStock.getStockId());
        clock.advance(Duration.ofMinutes(2));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> blocker = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject(
                                "select book_version_id from order_book_version where book_version_id = ? for update",
                                Long.class, versionId);
                        lockHeld.countDown();
                        try {
                            releaseLock.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                    }));
            assertThat(lockHeld.await(3, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(retentionService::deleteExpiredClosed)
                    .isInstanceOf(PessimisticLockingFailureException.class);

            releaseLock.countDown();
            blocker.get(3, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
        }

        retentionService.deleteExpiredClosed();
        assertThat(versionRepository.findById(versionId)).isEmpty();
    }

}
