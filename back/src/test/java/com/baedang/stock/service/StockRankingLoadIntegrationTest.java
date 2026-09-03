package com.baedang.stock.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.RankingEntry;
import com.baedang.stock.repository.StockRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 단위 테스트는 리포지토리가 목이라 <b>배치에 실린 값</b>까지만 보고, UPSERT 가 실제로 무엇을 바꿨는지는 보지 못한다.
 * 행이 있을 때와 없을 때 {@code ON CONFLICT} 분기가 갈리고,
 * {@code COALESCE} 가 기존 {@code prev_close} 를 지키는지,
 * 5초 수집기가 관리하는 {@code last_price} 가 보존되는지는 실제 DB 로만 검증할 수 있다.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "toss.enabled=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@Transactional
class StockRankingLoadIntegrationTest {

    private static final OffsetDateTime RANKED_AT =
            OffsetDateTime.of(2026, 9, 7, 8, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-07T08:00:05Z");



    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "schema.sql")
                            .toAbsolutePath().normalize()),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @Autowired StockRankingLoadService service;
    @Autowired StockRepository stockRepository;
    @Autowired QuoteSnapshotRepository quoteSnapshotRepository;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbcTemplate;

    /** collected_at 을 고정해 검증할 수 있도록 TimeConfig 의 Clock 을 대체한다. */
    @MockitoBean Clock clock;

    // 개발용 대역(Fake) 구현체가 없어졌으므로, 이 테스트가 관심 없는 MarketCalendarPort
    // 의존을 목(mock)으로 채워 넣어야 컨텍스트가 뜬다(다른 서비스가 직접 주입받는다).
    @MockitoBean MarketCalendarPort marketCalendarPort;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(NOW.toInstant());
    }

    @Test
    void 스냅샷이_없던_신규_편입_종목은_INSERT_된다() {
        Stock 신규 = saveStock(MarketCountry.KR, false);

        service.applyRanking(MarketCountry.KR, List.of(entry(1, 신규.getSymbol())), RANKED_AT);
        flushAndClear();

        assertThat(lastPrice(신규)).isEqualByComparingTo("70000");
        assertThat(prevClose(신규)).isEqualByComparingTo("69000");
        assertThat(currency(신규)).isEqualTo("KRW");
        // 주입된 Clock 기준 UTC 로 저장된다 (시스템 기본 타임존이 아니다).
        assertThat(collectedAt(신규).toInstant()).isEqualTo(NOW.toInstant());
        assertThat(stockRepository.findById(신규.getStockId()).orElseThrow().getIsRanked()).isTrue();
    }

    @Test
    void 스냅샷이_있던_신규_편입_종목은_prev_close만_UPDATE_된다() {
        // 랭킹 밖이었지만 누가 상세를 열어 on-demand 로 채워진 상태.
        Stock 신규 = saveStock(MarketCountry.KR, false);
        saveSnapshot(신규, "71000", "50000");

        service.applyRanking(MarketCountry.KR, List.of(entry(1, 신규.getSymbol())), RANKED_AT);
        flushAndClear();

        assertThat(prevClose(신규)).isEqualByComparingTo("69000");
        // 실시간 수집기가 관리하는 값이라 덮어쓰면 안 된다.
        assertThat(lastPrice(신규)).isEqualByComparingTo("71000");
    }

    @Test
    void 지난주에도_랭킹이던_종목의_prev_close는_그대로다() {
        Stock 유지 = saveStock(MarketCountry.KR, true);
        saveSnapshot(유지, "71000", "50000");

        service.applyRanking(MarketCountry.KR, List.of(entry(1, 유지.getSymbol())), RANKED_AT);
        flushAndClear();

        // 08:50 배치가 일봉 종가로 관리하는 값이므로 랭킹이 건드리지 않는다.
        assertThat(prevClose(유지)).isEqualByComparingTo("50000");
    }

    @Test
    void 기준가가_없으면_prev_close가_비어_있는_스냅샷이_INSERT_된다() {
        Stock 신규 = saveStock(MarketCountry.KR, false);

        service.applyRanking(
                MarketCountry.KR,
                List.of(entry(1, 신규.getSymbol(), new BigDecimal("70000"), BigDecimal.ZERO)),
                RANKED_AT);
        flushAndClear();

        // INSERT 는 ON CONFLICT 절을 타지 않으므로 기준가가 없으면 그대로 비어 있다.
        // 등락률을 0% 로 속이지 않고 null 로 내보내기 위한 것이다.
        assertThat(lastPrice(신규)).isEqualByComparingTo("70000");
        assertThat(prevClose(신규)).isNull();
    }

    @Test
    void 기준가가_없어도_이미_있던_prev_close는_지우지_않는다() {
        Stock 신규 = saveStock(MarketCountry.KR, false);
        saveSnapshot(신규, "71000", "50000");

        service.applyRanking(
                MarketCountry.KR,
                List.of(entry(1, 신규.getSymbol(), new BigDecimal("70000"), BigDecimal.ZERO)),
                RANKED_AT);
        flushAndClear();

        // UPSERT 가 COALESCE 없이 EXCLUDED 를 그대로 쓰면 멀쩡한 값이 NULL 이 되고 화면 등락률이 사라진다.
        assertThat(prevClose(신규)).isEqualByComparingTo("50000");
    }

    @Test
    void 현재가가_없으면_기존_행의_prev_close도_갱신하지_않는다() {
        Stock 신규 = saveStock(MarketCountry.KR, false);
        saveSnapshot(신규, "71000", "50000");

        service.applyRanking(
                MarketCountry.KR,
                List.of(entry(1, 신규.getSymbol(), null, new BigDecimal("69000"))),
                RANKED_AT);
        flushAndClear();

        // 현재가는 토스 응답의 필수 필드라, 비어 있으면 응답이 스펙과 다르다는 뜻이다.
        // 같은 엔트리의 기준가도 신뢰하지 않고 통째로 건너뛴다.
        assertThat(prevClose(신규)).isEqualByComparingTo("50000");
        assertThat(lastPrice(신규)).isEqualByComparingTo("71000");
    }

    @Test
    void 현재가가_없는_종목_때문에_유니버스_전체가_롤백되지_않는다() {
        Stock 정상 = saveStock(MarketCountry.KR, false);
        Stock 결측 = saveStock(MarketCountry.KR, false);

        // 배치는 전부 아니면 전무다. 걸러내지 않고 실어 보내면 NOT NULL 위반으로
        // 배치 전체가 죽고 트랜잭션이 롤백돼 이번 주 랭킹 적재가 통째로 날아간다.
        service.applyRanking(
                MarketCountry.KR,
                List.of(
                        entry(1, 정상.getSymbol()),
                        entry(2, 결측.getSymbol(), null, new BigDecimal("69000"))),
                RANKED_AT);
        flushAndClear();

        assertThat(prevClose(정상)).isEqualByComparingTo("69000");
        assertThat(quoteSnapshotRepository.findById(결측.getStockId())).isEmpty();
        // 시세를 못 채워도 랭킹 자체는 두 종목 모두 반영된다.
        assertThat(stockRepository.findById(결측.getStockId()).orElseThrow().getIsRanked()).isTrue();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private RankingEntry entry(int rank, String symbol) {
        return entry(rank, symbol, new BigDecimal("70000"), new BigDecimal("69000"));
    }

    private RankingEntry entry(
            int rank, String symbol, BigDecimal lastPrice, BigDecimal basePrice) {
        return new RankingEntry(
                rank,
                symbol,
                "KRW",
                lastPrice,
                basePrice,
                new BigDecimal("0.0145"),
                new BigDecimal("1000000"),
                new BigDecimal("70000000000")
        );
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
        QuoteSnapshot snapshot = new QuoteSnapshot(
                stock.getStockId(),
                new BigDecimal(lastPrice),
                stock.getCurrency(),
                RANKED_AT,
                RANKED_AT
        );
        snapshot.updatePrevClose(new BigDecimal(prevClose));
        quoteSnapshotRepository.saveAndFlush(snapshot);
    }

    private BigDecimal prevClose(Stock stock) {
        return column(stock, "prev_close", BigDecimal.class);
    }

    private BigDecimal lastPrice(Stock stock) {
        return column(stock, "last_price", BigDecimal.class);
    }

    private OffsetDateTime collectedAt(Stock stock) {
        return column(stock, "collected_at", OffsetDateTime.class);
    }

    private String currency(Stock stock) {
        return column(stock, "currency", String.class);
    }

    private <T> T column(Stock stock, String columnName, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT " + columnName + " FROM quote_snapshot WHERE stock_id = ?",
                type,
                stock.getStockId()
        );
    }
}
