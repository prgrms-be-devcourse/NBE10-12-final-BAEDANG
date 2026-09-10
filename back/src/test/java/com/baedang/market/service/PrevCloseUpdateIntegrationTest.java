package com.baedang.market.service;

import com.baedang.market.entity.*;
import com.baedang.market.port.*;
import com.baedang.market.repository.*;
import com.baedang.stock.entity.*;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "toss.enabled=false"})
class PrevCloseUpdateIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:latest-pg18").asCompatibleSubstituteFor("postgres"));
    @MockitoBean MarketCalendarPort calendars;
    @MockitoBean MarketDataPort data;
    @MockitoBean Clock clock;
    @Autowired PrevCloseUpdateService recovery;
    @Autowired DailyCandleSeedService seeder;
    @Autowired QuoteSnapshotPersistenceService prices;
    @Autowired DailyCandlePersistenceService daily;
    @Autowired DailyCandleRepository candles;
    @Autowired StockRepository stocks;
    @Autowired QuoteSnapshotRepository snapshots;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-12T05:00:00Z"));
        when(calendars.fetchUsMarketCalendar(any())).thenAnswer(i -> day(MarketCountry.US, i.getArgument(0)));
        when(calendars.fetchKrMarketCalendar(any())).thenAnswer(i -> day(MarketCountry.KR, i.getArgument(0)));
    }
    MarketCalendarDay day(MarketCountry country, LocalDate date) {
        boolean open = date.getDayOfWeek().getValue() < 6 && !date.equals(LocalDate.of(2026,9,7));
        var start = date.atTime(country == MarketCountry.US ? LocalTime.of(9,30) : LocalTime.of(9,0)).atZone(country.zoneId()).toOffsetDateTime();
        return new MarketCalendarDay(country,date,open,open?start:null,open?start.plusHours(6).plusMinutes(30):null,null);
    }
    Stock stock(MarketCountry country) {
        var stock = Stock.create(UUID.randomUUID().toString().substring(0,6), country,
                country==MarketCountry.US?"NASDAQ":"KOSPI", "test", null, country.defaultCurrency(), "STOCK", true);
        stock.applyRanking(1, BigDecimal.TEN);
        return stocks.saveAndFlush(stock);
    }
    Candle candle(Stock stock, String date, String price) {
        var at=day(stock.getMarketCountry(),LocalDate.parse(date)).regularOpenAt();
        var p=new BigDecimal(price);
        return new Candle(at,p,p,p,p,BigDecimal.ONE,stock.getCurrency());
    }
    void seed(Stock stock) {
        when(data.fetchCandles(stock.getSymbol(), CandleInterval.ONE_DAY, 200)).thenReturn(List.of(
                candle(stock,"2026-09-10","100"),candle(stock,"2026-09-11","110")));
        daily.upsert(stock.getStockId(),stock.getCurrency(),stock.getMarketCountry(),List.of(
                candle(stock,"2026-09-10","100"),candle(stock,"2026-09-11","110")), clock.instant());
    }
    @Test void weekendAndMidnightKeepTenPercentAndRecoveryIsIdempotent() {
        var stock=stock(MarketCountry.US); seed(stock);
        assertThat(recovery.recover(stock)).isTrue();
        var q=snapshots.findById(stock.getStockId()).orElseThrow();
        assertThat(q.getQuoteAt().atZoneSameInstant(stock.getMarketCountry().zoneId()).toLocalDate()).isEqualTo("2026-09-11");
        assertThat(q.getPrevCloseDate()).isEqualTo("2026-09-10");
        assertThat(q.changeRate()).isEqualByComparingTo("0.1");
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T00:00:00Z")); // Monday KST, Sunday New York
        assertThat(recovery.recover(stock)).isFalse();
        assertThat(snapshots.findById(stock.getStockId()).orElseThrow().changeRate()).isEqualByComparingTo("0.1");
        verify(data, times(1)).fetchCandles(stock.getSymbol(), CandleInterval.ONE_DAY, 200);
    }
    @Test void nineAmStartupFetchesMissingCloseAndDoesNotOverwriteTodaysQuote() {
        var stock=stock(MarketCountry.KR);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T00:05:00Z"));
        prices.saveOrUpdate(List.of(stock),List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("112"),
                clock.instant().atOffset(ZoneOffset.UTC),stock.getCurrency())),clock.instant().atOffset(ZoneOffset.UTC));
        assertThat(snapshots.findById(stock.getStockId()).orElseThrow().changeRate()).isNull();
        when(data.fetchCandles(stock.getSymbol(),CandleInterval.ONE_DAY,200)).thenReturn(List.of(
                candle(stock,"2026-09-10","100"),candle(stock,"2026-09-11","110")));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T00:05:01Z"));
        assertThat(recovery.recover(stock)).isTrue();
        var q=snapshots.findById(stock.getStockId()).orElseThrow();
        assertThat(q.getLastPrice()).isEqualByComparingTo("112");
        assertThat(q.getPrevClose()).isEqualByComparingTo("110");
        assertThat(q.getQuoteAt().atZoneSameInstant(stock.getMarketCountry().zoneId()).toLocalDate()).isEqualTo("2026-09-14");
    }
    @Test void newSessionClearsOldReferenceAndLatePreviousSessionCannotRevertIt() {
        var stock=stock(MarketCountry.US); seed(stock); recovery.recover(stock);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T14:00:00Z"));
        var at=clock.instant().atOffset(ZoneOffset.UTC);
        prices.saveOrUpdate(List.of(stock),List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("112"),at,"USD")),at);
        recovery.recover(stock);
        var q=snapshots.findById(stock.getStockId()).orElseThrow();
        assertThat(q.getPrevClose()).isEqualByComparingTo("110");
        assertThat(q.changeRate()).isEqualByComparingTo("0.018182");
        prices.saveOrUpdate(List.of(stock),List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("999"),
                OffsetDateTime.parse("2026-09-11T19:00:00Z"),"USD")),at);
        assertThat(snapshots.findById(stock.getStockId()).orElseThrow().getLastPrice()).isEqualByComparingTo("112");
    }
    @Test void afterHoursQuoteIsRejectedAndUnfinishedDailyCandleIsNotStored() {
        var stock=stock(MarketCountry.US);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-11T18:00:00Z"));
        daily.upsert(stock.getStockId(),"USD",MarketCountry.US,List.of(candle(stock,"2026-09-10","100"),candle(stock,"2026-09-11","105")), clock.instant());
        assertThat(candles.findByStockIdAndTradeDate(stock.getStockId(),LocalDate.of(2026,9,11))).isEmpty();
        var at=OffsetDateTime.parse("2026-09-11T12:00:00Z");
        prices.saveOrUpdate(List.of(stock),List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("999"),at,"USD")),clock.instant().atOffset(ZoneOffset.UTC));
        assertThat(snapshots.findById(stock.getStockId())).isEmpty();
        when(clock.instant()).thenReturn(Instant.parse("2026-09-11T20:11:00Z"));
        daily.upsert(stock.getStockId(),"USD",MarketCountry.US,List.of(candle(stock,"2026-09-11","110")), clock.instant());
        assertThat(candles.findByStockIdAndTradeDate(stock.getStockId(),LocalDate.of(2026,9,11))).isPresent();
    }
    @Test void lateUnfinishedResponseCannotOverwriteFinalClose() {
        var stock = stock(MarketCountry.US);
        var startedBeforeClose = Instant.parse("2026-09-11T19:00:00Z");
        when(clock.instant()).thenReturn(Instant.parse("2026-09-11T20:11:00Z"));
        daily.upsert(stock.getStockId(), "USD", MarketCountry.US,
                List.of(candle(stock, "2026-09-11", "110")), clock.instant());
        daily.upsert(stock.getStockId(), "USD", MarketCountry.US,
                List.of(candle(stock, "2026-09-11", "105")), startedBeforeClose);
        assertThat(candles.findByStockIdAndTradeDate(stock.getStockId(), LocalDate.of(2026,9,11)))
                .get().extracting(DailyCandle::getClosePrice).isEqualTo(new BigDecimal("110.0000"));
    }

    @Test void legacyCandleRemainsVisibleButCannotSuppressReferenceRefetch() {
        var stock = stock(MarketCountry.KR);
        jdbc.update("INSERT INTO daily_candle(stock_id,trade_date,open_price,high_price,low_price,close_price) VALUES (?,DATE '2026-09-11',90,90,90,90)", stock.getStockId());
        assertThat(candles.findByStockIdAndTradeDate(stock.getStockId(),LocalDate.of(2026,9,11))).isPresent();
        when(data.fetchCandles(stock.getSymbol(),CandleInterval.ONE_DAY,200)).thenReturn(List.of(
                candle(stock,"2026-09-10","100"), candle(stock,"2026-09-11","110")));
        assertThat(recovery.recover(stock)).isTrue();
        assertThat(snapshots.findById(stock.getStockId()).orElseThrow().changeRate()).isEqualByComparingTo("0.1");
    }

    @Test void missingExactReferenceDoesNotUseOlderDailyClose() {
        var stock = stock(MarketCountry.US);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T14:00:00Z"));
        daily.upsert(stock.getStockId(), "USD", MarketCountry.US,
                List.of(candle(stock, "2026-09-10", "100")), clock.instant());
        var at = clock.instant().atOffset(ZoneOffset.UTC);
        prices.saveOrUpdate(List.of(stock),List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("112"),at,"USD")),at);
        assertThat(snapshots.findById(stock.getStockId()).orElseThrow().changeRate()).isNull();
    }

    @Test void referenceRepairPreservesConcurrentSameTimestampPriceCorrection() {
        var stock = stock(MarketCountry.KR);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T00:05:00Z"));
        var at = clock.instant().atOffset(ZoneOffset.UTC);
        prices.saveOrUpdate(List.of(stock), List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("112"),at,"KRW")),at);
        var expected = snapshots.findById(stock.getStockId()).orElseThrow();
        prices.saveOrUpdate(List.of(stock), List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("113"),at,"KRW")),at.plusSeconds(1));
        daily.upsert(stock.getStockId(), "KRW", MarketCountry.KR,
                List.of(candle(stock, "2026-09-11", "110")), clock.instant());
        assertThat(prices.repairReference(stock, expected, candles.findByStockIdAndTradeDate(stock.getStockId(), LocalDate.of(2026,9,11)).orElseThrow())).isEqualTo(1);
        var repaired = snapshots.findById(stock.getStockId()).orElseThrow();
        assertThat(repaired.getLastPrice()).isEqualByComparingTo("113");
        assertThat(repaired.getCollectedAt().toInstant()).isEqualTo(at.plusSeconds(1).toInstant());
        assertThat(repaired.getPrevClose()).isEqualByComparingTo("110");
        assertThat(prices.repairReference(stock, expected, candles.findByStockIdAndTradeDate(stock.getStockId(), LocalDate.of(2026,9,11)).orElseThrow())).isZero();
    }

    @Test void newTradingDayWithoutReferenceClearsPreviousDaysBaseline() {
        var stock = stock(MarketCountry.US);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-11T18:00:00Z"));
        daily.upsert(stock.getStockId(), "USD", MarketCountry.US,
                List.of(candle(stock, "2026-09-10", "100")), clock.instant());
        var friday = clock.instant().atOffset(ZoneOffset.UTC);
        prices.saveOrUpdate(List.of(stock),List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("110"),friday,"USD")),friday);
        var previous = snapshots.findById(stock.getStockId()).orElseThrow();
        prices.repairReference(stock, previous, candles.findByStockIdAndTradeDate(stock.getStockId(), LocalDate.of(2026,9,10)).orElseThrow());
        assertThat(snapshots.findById(stock.getStockId()).orElseThrow().changeRate()).isEqualByComparingTo("0.1");
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T14:00:00Z"));
        var monday = clock.instant().atOffset(ZoneOffset.UTC);
        prices.saveOrUpdate(List.of(stock),List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("112"),monday,"USD")),monday);
        var quote = snapshots.findById(stock.getStockId()).orElseThrow();
        assertThat(quote.getPrevCloseDate()).isNull();
        assertThat(quote.changeRate()).isNull();
        assertThat(prices.repairReference(stock, new QuoteSnapshot(stock.getStockId(),BigDecimal.ONE,"USD",friday,friday), candles.findByStockIdAndTradeDate(stock.getStockId(), LocalDate.of(2026,9,10)).orElseThrow())).isZero();
    }

    @Test void oldDailyRowCannotSupplyMissingReferenceWhenFetchOmitsIt() {
        var stock = stock(MarketCountry.KR);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T00:05:00Z"));
        jdbc.update("INSERT INTO daily_candle(stock_id,trade_date,open_price,high_price,low_price,close_price) VALUES (?,DATE '2026-09-11',90,90,90,90)", stock.getStockId());
        var at = clock.instant().atOffset(ZoneOffset.UTC);
        prices.saveOrUpdate(List.of(stock), List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("112"),at,"KRW")),at);
        when(data.fetchCandles(stock.getSymbol(), CandleInterval.ONE_DAY, 200)).thenReturn(List.of());
        assertThat(recovery.recover(stock)).isFalse();
        assertThat(snapshots.findById(stock.getStockId()).orElseThrow().getPrevClose()).isNull();
        assertThat(candles.findByStockIdAndTradeDate(stock.getStockId(),LocalDate.of(2026,9,11))).isPresent();
        when(data.fetchCandles(stock.getSymbol(), CandleInterval.ONE_DAY, 200)).thenReturn(List.of(candle(stock,"2026-09-11","110")));
        assertThat(recovery.recover(stock)).isTrue();
        assertThat(snapshots.findById(stock.getStockId()).orElseThrow().getPrevClose()).isEqualByComparingTo("110");
    }

    @Test void mismatchedDateIsHiddenEvenIfRefetchFails() {
        var stock = stock(MarketCountry.KR);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T00:05:00Z"));
        var at = clock.instant().atOffset(ZoneOffset.UTC);
        prices.saveOrUpdate(List.of(stock), List.of(new PriceQuote(stock.getSymbol(),new BigDecimal("112"),at,"KRW")),at);
        jdbc.update("UPDATE quote_snapshot SET prev_close=90,prev_close_date=DATE '2026-09-10' WHERE stock_id=?",stock.getStockId());
        when(data.fetchCandles(stock.getSymbol(), CandleInterval.ONE_DAY, 200)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> recovery.recover(stock)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshots.findById(stock.getStockId()).orElseThrow().changeRate()).isNull();
    }

    @Test void seedAndReferenceRecoverySerializeExternalFetchThroughCommittedStorage() throws Exception {
        jdbc.update("UPDATE stock SET is_ranked=false");
        var stock = stock(MarketCountry.US);
        var firstEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseFirst = new java.util.concurrent.CountDownLatch(1);
        var secondEntered = new java.util.concurrent.CountDownLatch(1);
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        when(data.fetchCandles(stock.getSymbol(), CandleInterval.ONE_DAY, 200)).thenAnswer(call -> {
            if (sequence.incrementAndGet() == 1) {
                firstEntered.countDown();
                assertThat(releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return List.of(candle(stock,"2026-09-11","100"));
            }
            secondEntered.countDown();
            assertThat(candles.findByStockIdAndTradeDate(stock.getStockId(),LocalDate.of(2026,9,11)))
                    .get().extracting(DailyCandle::getClosePrice).isEqualTo(new BigDecimal("100.0000"));
            return List.of(candle(stock,"2026-09-10","100"),candle(stock,"2026-09-11","110"));
        });
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> seeder.seed(MarketCountry.US));
            try {
                assertThat(firstEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> recovery.recover(stock));
                assertThat(secondEntered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
                releaseFirst.countDown();
                assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).success()).isEqualTo(1);
                assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally {
                releaseFirst.countDown();
            }
        }
        assertThat(candles.findByStockIdAndTradeDate(stock.getStockId(),LocalDate.of(2026,9,11)))
                .get().extracting(DailyCandle::getClosePrice).isEqualTo(new BigDecimal("110.0000"));
    }

    @Test void referenceRecoverySurvivesNewerQuotesWithinSameExchangeTradingDay() {
        for (var country : MarketCountry.values()) {
            var stock = stock(country);
            // US crosses KST midnight while remaining on Monday in New York.
            var started = country == MarketCountry.KR
                    ? Instant.parse("2026-09-14T00:05:00Z") : Instant.parse("2026-09-14T14:59:59Z");
            when(clock.instant()).thenReturn(started);
            var before = started.atOffset(ZoneOffset.UTC);
            prices.saveOrUpdate(List.of(stock), List.of(new PriceQuote(stock.getSymbol(),
                    new BigDecimal("112"), before, stock.getCurrency())), before);
            when(data.fetchCandles(stock.getSymbol(), CandleInterval.ONE_DAY, 200)).thenAnswer(call -> {
                var advanced = started.plusSeconds(10);
                when(clock.instant()).thenReturn(advanced);
                var after = advanced.atOffset(ZoneOffset.UTC);
                prices.saveOrUpdate(List.of(stock), List.of(new PriceQuote(stock.getSymbol(),
                        new BigDecimal("113"), after, stock.getCurrency())), after);
                return List.of(candle(stock, "2026-09-11", "110"));
            });

            assertThat(recovery.recover(stock)).isTrue();
            var repaired = snapshots.findById(stock.getStockId()).orElseThrow();
            assertThat(repaired.getLastPrice()).isEqualByComparingTo("113");
            assertThat(repaired.getQuoteAt().toInstant()).isEqualTo(started.plusSeconds(10));
            assertThat(repaired.getCollectedAt().toInstant()).isEqualTo(started.plusSeconds(10));
            assertThat(repaired.getPrevCloseDate()).isEqualTo("2026-09-11");
            assertThat(repaired.getPrevClose()).isEqualByComparingTo("110");
        }
    }

}
