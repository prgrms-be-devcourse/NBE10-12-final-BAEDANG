package com.baedang.stock.service;

import com.baedang.market.entity.DailyCandle;
import com.baedang.market.entity.MinuteCandle;
import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.MarketCalendarPort;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.repository.CandleAggregateRepository;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.repository.MinuteCandleRepository;
import com.baedang.market.service.LatestCompletedTradingDayResolver;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg18")
                    .asCompatibleSubstituteFor("postgres"));

    @MockitoBean MarketDataPort marketDataPort;
    @MockitoBean LatestCompletedTradingDayResolver latestCompletedTradingDayResolver;
    // 개발용 대역(Fake) 구현체가 없어졌으므로, 이 테스트가 관심 없는 MarketCalendarPort
    // 의존을 목(mock)으로 채워 넣어야 컨텍스트가 뜬다(다른 서비스가 직접 주입받는다).
    @MockitoBean MarketCalendarPort marketCalendarPort;

    @Autowired CandleQueryService candleQueryService;
    @Autowired MinuteCandlePersistenceService persistenceService;
    @Autowired StockRepository stockRepository;
    @Autowired DailyCandleRepository dailyCandleRepository;
    @Autowired CandleAggregateRepository candleAggregateRepository;
    @Autowired MinuteCandleRepository minuteCandleRepository;
    @Autowired JdbcClient jdbcClient;

    @BeforeEach
    void configureCalendar() {
        when(latestCompletedTradingDayResolver.resolve(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 9, 15)));
        when(marketCalendarPort.fetchUsMarketCalendar(ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    LocalDate date = call.getArgument(0);
                    OffsetDateTime open = date.atTime(9, 30).atZone(MarketCountry.US.zoneId()).toOffsetDateTime();
                    return new MarketCalendarDay(MarketCountry.US, date, true, open, open.plusHours(6).plusMinutes(30), null);
                });
    }

    @BeforeAll
    static void disableAutomaticPolicies(@Autowired JdbcClient jdbcClient) {
        // 테스트는 백그라운드 자동 집계가 필요 없고, 테스트 코드가 직접 refreshAggregate()로
        // 수동 집계하여 검증한다. 테스트 실행 중 백그라운드 스케줄러와의 경합을 원천 차단하기 위해
        // 마이그레이션이 등록한 자동 갱신 정책을 테스트 시작 시 모두 제거한다.
        jdbcClient.sql("SELECT remove_continuous_aggregate_policy('candle_5m', if_exists => true)").query().listOfRows();
        jdbcClient.sql("SELECT remove_continuous_aggregate_policy('candle_10m', if_exists => true)").query().listOfRows();
        jdbcClient.sql("SELECT remove_continuous_aggregate_policy('candle_1w', if_exists => true)").query().listOfRows();
    }

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
    void 최초_일봉_차트가_200개를_한번만_백필하고_기간_변경은_DB를_재사용한다() {
        Stock stock = saveStock(MarketCountry.US, "USD");
        LocalDate latestDate = LocalDate.of(2026, 8, 28);
        List<Candle> fetched = IntStream.range(0, 200)
                .mapToObj(index -> dailyCandle(latestDate.minusDays(index), "100", "USD"))
                .toList();
        AtomicBoolean externalCallInTransaction = new AtomicBoolean(true);
        when(marketDataPort.fetchCandles(
                stock.getSymbol(), CandleInterval.ONE_DAY, 200))
                .thenAnswer(invocation -> {
                    externalCallInTransaction.set(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return fetched;
                });
        when(latestCompletedTradingDayResolver.resolve(MarketCountry.US))
                .thenReturn(Optional.of(latestDate));

        var oneMonth = candleQueryService.getCandles(
                stock.getSymbol(), "US", "1d", "1M");
        var sixMonths = candleQueryService.getCandles(
                stock.getSymbol(), "US", "1d", "6M");
        var oneYear = candleQueryService.getCandles(
                stock.getSymbol(), "US", "1d", "1Y");

        assertThat(externalCallInTransaction).isFalse();
        assertThat(oneMonth.items()).hasSize(22);
        assertThat(sixMonths.items()).hasSize(130);
        assertThat(oneYear.items()).hasSize(200);
        assertThat(dailyCandleRepository.hasAtLeastCandles(stock.getStockId(), 200)).isTrue();
        assertThat(dailyCandleRepository.hasAtLeastCandles(stock.getStockId(), 201)).isFalse();
        verify(marketDataPort, times(1)).fetchCandles(
                stock.getSymbol(), CandleInterval.ONE_DAY, 200);
    }

    @Test
    void 분봉_외부조회는_트랜잭션밖에서_실행하고_중복키를_UPSERT한다() {
        Stock stock = saveStock(MarketCountry.US, "USD");
        OffsetDateTime at = OffsetDateTime.parse("2026-09-10T10:00:00-04:00");
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
        persistenceService.upsert(stock.getStockId(), stock.getMarketCountry(), List.of(corrected));

        assertThat(externalCallInTransaction).isFalse();
        assertThat(response.items()).hasSize(1);
        assertThat(minuteCandleRepository.findTopByStockIdOrderByCandleAtDesc(stock.getStockId()))
                .get()
                .extracting(row -> row.getClosePrice())
                .satisfies(value -> assertThat((BigDecimal) value).isEqualByComparingTo("105"));
    }

    @Test
    void 오분봉_뷰가_일분봉_다섯개를_하나의_OHLCV로_묶는다() {
        Stock stock = saveStock(MarketCountry.KR, "KRW");
        // 09:00~09:04 가 한 봉, 09:05 가 다음 봉. 고가·저가가 봉 가운데에 오도록 섞는다.
        minuteCandleRepository.saveAll(List.of(
                minute(stock, kst(2026, 8, 27, 9, 0), "100", "101", "99", "100"),
                minute(stock, kst(2026, 8, 27, 9, 1), "100", "130", "99", "120"),
                minute(stock, kst(2026, 8, 27, 9, 2), "120", "125", "80", "90"),
                minute(stock, kst(2026, 8, 27, 9, 3), "90", "95", "85", "95"),
                minute(stock, kst(2026, 8, 27, 9, 4), "95", "110", "90", "105"),
                minute(stock, kst(2026, 8, 27, 9, 5), "105", "106", "104", "106")));
        refreshAggregate("candle_5m");

        var response = candleQueryService.getCandles(stock.getSymbol(), "KR", "5m", "1D");

        assertThat(response.items()).hasSize(2);
        var first = response.items().get(0);
        assertThat(first.at()).isEqualTo(kst(2026, 8, 27, 9, 0));
        assertThat(new BigDecimal(first.open())).isEqualByComparingTo("100");   // 첫 봉의 시가
        assertThat(new BigDecimal(first.high())).isEqualByComparingTo("130");   // 다섯 봉의 최고가
        assertThat(new BigDecimal(first.low())).isEqualByComparingTo("80");     // 다섯 봉의 최저가
        assertThat(new BigDecimal(first.close())).isEqualByComparingTo("105");  // 마지막 봉의 종가
        assertThat(new BigDecimal(first.volume())).isEqualByComparingTo("5000");
        assertThat(response.items().get(1).at()).isEqualTo(kst(2026, 8, 27, 9, 5));
    }

    @Test
    void 십분봉_뷰는_십분_경계로_묶는다() {
        Stock stock = saveStock(MarketCountry.KR, "KRW");
        // 5분봉은 3봉으로, 10분봉은 2봉으로 묶이게끔 1분봉 데이터를 추가한다.
        // (10분봉은 9:00~9:10이 한 봉이라 9:00과 9:07인 1분봉이 하나로 묶인다)
        minuteCandleRepository.saveAll(List.of(
                minute(stock, kst(2026, 8, 27, 9, 0), "100", "100", "100", "100"),
                minute(stock, kst(2026, 8, 27, 9, 7), "200", "200", "200", "200"),
                minute(stock, kst(2026, 8, 27, 9, 12), "300", "300", "300", "300")));
        refreshAggregate("candle_10m");

        var response = candleQueryService.getCandles(stock.getSymbol(), "KR", "10m", "1W");

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).at()).isEqualTo(kst(2026, 8, 27, 9, 0));
        assertThat(new BigDecimal(response.items().get(0).close())).isEqualByComparingTo("200");
        assertThat(response.items().get(1).at()).isEqualTo(kst(2026, 8, 27, 9, 10));
    }

    @Test
    void 주봉은_월요일이_휴장이어도_그_주에_묶고_KST_자정을_돌려준다() {
        Stock stock = saveStock(MarketCountry.KR, "KRW");
        // 8/24(월)은 휴장이라 행이 없다. 화~금만 있어도 봉은 8/24 주에 붙어야 한다.
        dailyCandleRepository.saveAll(List.of(
                daily(stock, LocalDate.of(2026, 8, 25), "100"),
                daily(stock, LocalDate.of(2026, 8, 26), "110"),
                daily(stock, LocalDate.of(2026, 8, 27), "120"),
                daily(stock, LocalDate.of(2026, 8, 28), "130"),
                daily(stock, LocalDate.of(2026, 8, 31), "140")));
        refreshAggregate("candle_1w");

        var response = candleQueryService.getCandles(stock.getSymbol(), "KR", "1w", "6M");

        assertThat(response.items()).hasSize(2);
        // 봉 시각은 서버 타임존이 아니라 KST 자정이어야 한다(일봉 응답과 같은 기준).
        assertThat(response.items().get(0).at()).isEqualTo(kst(2026, 8, 24, 0, 0));
        assertThat(new BigDecimal(response.items().get(0).close())).isEqualByComparingTo("130");
        assertThat(response.items().get(1).at()).isEqualTo(kst(2026, 8, 31, 0, 0));
    }

    @Test
    void 미국_서머타임이_끝나도_장시작_분봉이_제_봉으로_묶인다() {
        Stock stock = saveStock(MarketCountry.US, "USD");
        // 미국은 서머타임이 있다: 1년에 한 번 하루가 1시간 앞당겨지고(그날은 23시간), 한 번 되돌아간다(그날은 25시간).
        // (ex. 09:30 ET는 서머타임 종료(11/1) 전에는 13:30 UTC, 후에는 14:30 UTC)
        minuteCandleRepository.saveAll(List.of(
                minute(stock, et(2026, 10, 30, 9, 30), "10", "10", "10", "10"),
                minute(stock, et(2026, 10, 30, 9, 31), "11", "11", "11", "11"),
                minute(stock, et(2026, 11, 2, 9, 30), "20", "20", "20", "20"),
                minute(stock, et(2026, 11, 2, 9, 31), "21", "21", "21", "21")));
        refreshAggregate("candle_5m");

        var response = candleQueryService.getCandles(stock.getSymbol(), "US", "5m", "1W");

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).at().toInstant())
                .isEqualTo(Instant.parse("2026-10-30T13:30:00Z"));
        assertThat(response.items().get(1).at().toInstant())
                .isEqualTo(Instant.parse("2026-11-02T14:30:00Z"));
        assertThat(new BigDecimal(response.items().get(1).close())).isEqualByComparingTo("21");
    }

    /**
     * 온디맨드 백필 직후 호출되는 경로를 실제 DB 로 확인한다.
     * 단위 테스트는 목이라 프록시를 안 타므로, {@code Propagation.NEVER} 와
     * {@code CALL refresh_continuous_aggregate} 가 실제로 도는지는 여기서만 검증된다.
     */
    @Test
    void 주봉_즉시_갱신을_호출하면_저장된_일봉이_주봉으로_조회된다() {
        Stock stock = saveStock(MarketCountry.KR, "KRW");
        dailyCandleRepository.saveAll(List.of(
                daily(stock, LocalDate.of(2026, 8, 25), "100"),
                daily(stock, LocalDate.of(2026, 8, 31), "140")));

        candleAggregateRepository.refreshWeekly();

        var response = candleQueryService.getCandles(stock.getSymbol(), "KR", "1w", "6M");

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).at()).isEqualTo(kst(2026, 8, 24, 0, 0));
        assertThat(new BigDecimal(response.items().get(0).close())).isEqualByComparingTo("100");
        assertThat(response.items().get(1).at()).isEqualTo(kst(2026, 8, 31, 0, 0));
    }

    private void refreshAggregate(String view) {
        // 뷰는 WITH NO DATA 로 만들고 갱신 정책(백그라운드 잡)이 채운다.
        // 테스트에서는 잡을 기다리지 않고 직접 새로고침한다.
        jdbcClient.sql("CALL refresh_continuous_aggregate('" + view + "', NULL, NULL)").update();
    }

    private MinuteCandle minute(
            Stock stock, OffsetDateTime at, String open, String high, String low, String close) {
        return new MinuteCandle(stock.getStockId(), at,
                new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), new BigDecimal("1000"));
    }

    private OffsetDateTime kst(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    }

    private OffsetDateTime et(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.of("America/New_York")).toOffsetDateTime();
    }

    private Stock saveStock(MarketCountry country, String currency) {
        String symbol = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return stockRepository.save(Stock.create(
                symbol, country, country == MarketCountry.KR ? "KOSPI" : "NASDAQ",
                "테스트 종목", null, currency, "STOCK", true));
    }

    private DailyCandle daily(Stock stock, LocalDate date, String close) {
        BigDecimal price = new BigDecimal(close);
        DailyCandle row = new DailyCandle(
                stock.getStockId(), date, price, price, price, price, new BigDecimal("1000"));
        return row;
    }

    private Candle candle(OffsetDateTime at, String close, String currency) {
        BigDecimal price = new BigDecimal(close);
        return new Candle(at, price, price, price, price, new BigDecimal("1000"), currency);
    }

    private Candle dailyCandle(LocalDate date, String close, String currency) {
        return candle(date.atTime(9, 30).atZone(currency.equals("USD") ? MarketCountry.US.zoneId() : MarketCountry.KR.zoneId()).toOffsetDateTime(), close, currency);
    }
}
