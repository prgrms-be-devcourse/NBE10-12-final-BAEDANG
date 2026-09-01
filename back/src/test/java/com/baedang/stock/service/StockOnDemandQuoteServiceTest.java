package com.baedang.stock.service;

import com.baedang.market.entity.DailyCandle;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.DailyCandleRepository;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.market.service.DailyCandlePersistenceService;
import com.baedang.market.service.LatestCompletedTradingDayResolver;
import com.baedang.market.service.QuoteSnapshotPersistenceService;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link StockOnDemandQuoteService} 단위 테스트 — 이슈 #75, #83.
 *
 * <p>랭킹 상위 100의 시세는 그대로 통과하고, 일봉은 랭킹 여부와 관계없이 상세·차트가
 * 공유할 최신 200개를 한 번만 온디맨드로 채워야 한다.
 */
@ExtendWith(MockitoExtension.class)
class StockOnDemandQuoteServiceTest {

    // 2026-08-31 12:00 KST.
    private static final Instant NOW = Instant.parse("2026-08-31T03:00:00Z");

    @Mock MarketDataPort marketDataPort;
    @Mock QuoteSnapshotRepository quoteSnapshotRepository;
    @Mock QuoteSnapshotPersistenceService quoteSnapshotPersistenceService;
    @Mock DailyCandleRepository dailyCandleRepository;
    @Mock DailyCandlePersistenceService dailyCandlePersistenceService;
    @Mock LatestCompletedTradingDayResolver latestCompletedTradingDayResolver;
    @Mock Stock stock;

    private StockOnDemandQuoteService service;

    @BeforeEach
    void setUp() {
        service = new StockOnDemandQuoteService(
                marketDataPort,
                quoteSnapshotRepository,
                quoteSnapshotPersistenceService,
                dailyCandleRepository,
                dailyCandlePersistenceService,
                new DailyCandleBackfillTracker(),
                latestCompletedTradingDayResolver,
                Clock.fixed(NOW, ZoneOffset.UTC));
        // 테스트마다 실제로 쓰는 stub 조합이 달라서(예: 랭킹 안 종목 조기 반환 경로는
        // symbol/currency를 아예 안 읽는다) 공용 stub은 lenient로 둔다.
        lenient().when(stock.getStockId()).thenReturn(10L);
        lenient().when(stock.getSymbol()).thenReturn("005930");
        lenient().when(stock.getCurrency()).thenReturn("KRW");
        lenient().when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
    }

    @Test
    void 랭킹_상위_100_종목은_일봉을_확인하되_시세는_그대로_통과한다() {
        when(stock.getIsRanked()).thenReturn(true);
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(200L);
        QuoteSnapshot existing = quote(LocalDate.of(2020, 1, 1));

        QuoteSnapshot result = service.ensureQuote(stock, existing);

        assertThat(result).isSameAs(existing);
        verify(dailyCandleRepository).countByStockId(10L);
        verifyNoInteractions(marketDataPort, quoteSnapshotPersistenceService, dailyCandlePersistenceService);
    }

    @Test
    void 랭킹_밖_종목이면서_시세가_없으면_온디맨드로_채운다() {
        when(stock.getIsRanked()).thenReturn(false);
        Candle dailyCandle = candle(LocalDate.of(2026, 8, 28), "236050"); // 어제(평일) 종가
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, 200))
                .thenReturn(List.of(dailyCandle));
        when(marketDataPort.fetchPrices(List.of("005930")))
                .thenReturn(List.of(new PriceQuote("005930", new BigDecimal("241500"), OffsetDateTime.now(), "KRW")));

        QuoteSnapshot refreshed = quote(LocalDate.of(2026, 8, 28));
        when(quoteSnapshotRepository.findById(10L)).thenReturn(Optional.of(refreshed));
        when(quoteSnapshotRepository.save(refreshed)).thenReturn(refreshed);
        // 백필(존재 확인 → 락 안 재확인) 전까지는 일봉이 하나도 없다가, upsert가 호출된
        // 뒤부터는(이중 확인 락 안에서도 한 번 더 조회한다) 방금 채운 일봉이 보여야 한다 —
        // 언제 몇 번 조회하든 항상 최신 상태를 반영하도록 상태 기반(stateful)으로 stub한다.
        AtomicBoolean backfilled = new AtomicBoolean(false);
        DailyCandle backfilledCandle = new DailyCandle(10L, LocalDate.of(2026, 8, 28),
                new BigDecimal("236050"), new BigDecimal("236050"),
                new BigDecimal("236050"), new BigDecimal("236050"), new BigDecimal("1000"));
        when(dailyCandleRepository.findByStockIdOrderByTradeDateDesc(eq(10L), any()))
                .thenAnswer(invocation -> backfilled.get() ? List.of(backfilledCandle) : List.of());
        doAnswer(invocation -> {
            backfilled.set(true);
            return null;
        }).when(dailyCandlePersistenceService).upsert(eq(10L), eq("KRW"), any());

        QuoteSnapshot result = service.ensureQuote(stock, null);

        verify(dailyCandlePersistenceService).upsert(10L, "KRW", List.of(dailyCandle));
        verify(quoteSnapshotPersistenceService).saveOrUpdate(eq(List.of(stock)), anyList(), any());
        assertThat(result).isSameAs(refreshed);
        verify(refreshed).updatePrevClose(new BigDecimal("236050"));
        // findById가 반환하는 스냅샷은 그 시점의 읽기 전용 트랜잭션 밖에서는 detached
        // 상태라, updatePrevClose만으로는 DB에 반영되지 않는다 — 명시적 save가 꼭 필요하다
        // (제미나이 코드 리뷰, PR #80).
        verify(quoteSnapshotRepository).save(refreshed);
    }

    @Test
    void 시세가_오늘_이미_수집됐으면_다시_조회하지_않는다() {
        when(stock.getIsRanked()).thenReturn(false);
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(200L);
        QuoteSnapshot todayQuote = quoteCollectedAt(OffsetDateTime.parse("2026-08-31T10:00:00+09:00"));

        QuoteSnapshot result = service.ensureQuote(stock, todayQuote);

        assertThat(result).isSameAs(todayQuote);
        verify(marketDataPort, never()).fetchPrices(any());
        // 일봉은 이미 있으므로 백필도 일어나지 않는다.
        verify(marketDataPort, never()).fetchCandles(any(), any(), anyInt());
    }

    @Test
    void 시세가_어제_수집됐으면_오늘_다시_조회한다() {
        when(stock.getIsRanked()).thenReturn(false);
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(200L);
        QuoteSnapshot yesterdayQuote = quoteCollectedAt(OffsetDateTime.parse("2026-08-30T10:00:00+09:00"));
        when(marketDataPort.fetchPrices(List.of("005930")))
                .thenReturn(List.of(new PriceQuote("005930", new BigDecimal("105"), OffsetDateTime.now(), "KRW")));
        when(quoteSnapshotRepository.findById(10L)).thenReturn(Optional.of(yesterdayQuote));

        service.ensureQuote(stock, yesterdayQuote);

        verify(marketDataPort).fetchPrices(List.of("005930"));
        verify(quoteSnapshotPersistenceService).saveOrUpdate(eq(List.of(stock)), anyList(), any());
    }

    @Test
    void 일봉이_200개_이상이면_백필하지_않는다() {
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(200L);

        service.ensureDailyCandles(stock);

        verify(marketDataPort, never()).fetchCandles(any(), any(), anyInt());
        verifyNoInteractions(dailyCandlePersistenceService);
    }

    @Test
    void 과거_일봉이_200개여도_최신_확정_거래일보다_오래됐으면_다시_백필한다() {
        LocalDate expectedTradeDate = LocalDate.of(2026, 8, 31);
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(200L);
        when(latestCompletedTradingDayResolver.resolve(MarketCountry.KR))
                .thenReturn(Optional.of(expectedTradeDate));
        when(dailyCandleRepository.findTopByStockIdOrderByTradeDateDesc(10L))
                .thenReturn(Optional.of(new DailyCandle(
                        10L, expectedTradeDate.minusDays(1), BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, 200))
                .thenReturn(List.of(candle(expectedTradeDate, "100")));

        service.ensureDailyCandles(stock);

        verify(marketDataPort).fetchCandles("005930", CandleInterval.ONE_DAY, 200);
        verify(dailyCandlePersistenceService).upsert(eq(10L), eq("KRW"), any());
    }

    @Test
    void 같은_확정_거래일의_최신화가_성공했으면_DB가_아직_과거여도_다시_호출하지_않는다() {
        LocalDate expectedTradeDate = LocalDate.of(2026, 8, 31);
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(200L);
        when(latestCompletedTradingDayResolver.resolve(MarketCountry.KR))
                .thenReturn(Optional.of(expectedTradeDate));
        when(dailyCandleRepository.findTopByStockIdOrderByTradeDateDesc(10L))
                .thenReturn(Optional.of(new DailyCandle(
                        10L, expectedTradeDate.minusDays(1), BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, 200))
                .thenReturn(List.of(candle(expectedTradeDate.minusDays(1), "100")));

        service.ensureDailyCandles(stock);
        service.ensureDailyCandles(stock);

        verify(marketDataPort, times(1)).fetchCandles("005930", CandleInterval.ONE_DAY, 200);
        verify(dailyCandlePersistenceService, times(1)).upsert(eq(10L), eq("KRW"), any());
    }

    @Test
    void 최신_확정_거래일의_일봉까지_있으면_백필하지_않는다() {
        LocalDate expectedTradeDate = LocalDate.of(2026, 8, 31);
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(200L);
        when(latestCompletedTradingDayResolver.resolve(MarketCountry.KR))
                .thenReturn(Optional.of(expectedTradeDate));
        when(dailyCandleRepository.findTopByStockIdOrderByTradeDateDesc(10L))
                .thenReturn(Optional.of(new DailyCandle(
                        10L, expectedTradeDate, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));

        service.ensureDailyCandles(stock);

        verify(marketDataPort, never()).fetchCandles(any(), any(), anyInt());
        verifyNoInteractions(dailyCandlePersistenceService);
    }

    /**
     * 제미나이 코드 리뷰(PR #80)가 지적한 동시 요청 경합을 재현한다. 토큰 발급 지연을
     * 흉내낼 때(TossSecuritiesClientTest)와 같은 이유로 {@code fetchCandles}에 인위적인
     * 지연을 줘서, 여러 스레드가 "아직 일봉이 없다"는 판단을 동시에 내리도록 만든다.
     * 락이 없으면 이 판단이 겹쳐서 여러 스레드가 각자 Toss를 호출하게 된다.
     */
    @Test
    void 동시_요청에서도_일봉_백필은_한_번만_일어난다() throws Exception {
        AtomicBoolean backfilled = new AtomicBoolean(false);
        when(dailyCandleRepository.countByStockId(10L))
                .thenAnswer(invocation -> backfilled.get() ? 200L : 0L);
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, 200))
                .thenAnswer(invocation -> {
                    Thread.sleep(100); // 스레드들이 락 앞에서 실제로 겹치도록 지연시킨다.
                    return List.of(candle(LocalDate.of(2026, 8, 28), "100"));
                });
        doAnswer(invocation -> {
            backfilled.set(true);
            return null;
        }).when(dailyCandlePersistenceService).upsert(eq(10L), eq("KRW"), any());

        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                service.ensureDailyCandles(stock);
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();

        verify(marketDataPort, times(1)).fetchCandles("005930", CandleInterval.ONE_DAY, 200);
    }

    @Test
    void 랭킹_상위_100_종목도_상세_진입시_일봉이_부족하면_백필한다() {
        when(stock.getIsRanked()).thenReturn(true);
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, 200))
                .thenReturn(List.of(candle(LocalDate.of(2026, 8, 28), "100")));
        QuoteSnapshot existing = quote(LocalDate.of(2026, 8, 28));

        QuoteSnapshot result = service.ensureQuote(stock, existing);

        assertThat(result).isSameAs(existing);
        verify(marketDataPort).fetchCandles("005930", CandleInterval.ONE_DAY, 200);
        verify(dailyCandlePersistenceService).upsert(eq(10L), eq("KRW"), any());
        verify(marketDataPort, never()).fetchPrices(any());
    }

    @Test
    void 신규_상장_종목의_일봉이_200개보다_적어도_성공한_백필을_재사용한다() {
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(0L);
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, 200))
                .thenReturn(List.of(candle(LocalDate.of(2026, 8, 28), "100")));

        service.ensureDailyCandles(stock);
        service.ensureDailyCandles(stock);

        verify(marketDataPort, times(1)).fetchCandles("005930", CandleInterval.ONE_DAY, 200);
        verify(dailyCandlePersistenceService, times(1)).upsert(eq(10L), eq("KRW"), any());
    }

    @Test
    void 실패한_일봉_백필은_캐시하지_않아_다음_요청에서_재시도한다() {
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(0L);
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, 200))
                .thenThrow(new RuntimeException("Toss 장애"));

        service.ensureDailyCandles(stock);
        service.ensureDailyCandles(stock);

        verify(marketDataPort, times(2)).fetchCandles("005930", CandleInterval.ONE_DAY, 200);
        verifyNoInteractions(dailyCandlePersistenceService);
    }

    @Test
    void 시세_조회가_실패해도_예외를_던지지_않고_기존_값을_돌려준다() {
        when(stock.getIsRanked()).thenReturn(false);
        when(dailyCandleRepository.countByStockId(10L)).thenReturn(200L);
        when(marketDataPort.fetchPrices(any())).thenThrow(new RuntimeException("Toss 장애"));
        when(quoteSnapshotRepository.findById(10L)).thenReturn(Optional.empty());

        QuoteSnapshot result = service.ensureQuote(stock, null);

        assertThat(result).isNull();
        verifyNoInteractions(quoteSnapshotPersistenceService);
    }

    private QuoteSnapshot quote(LocalDate collectedDateKst) {
        return quoteCollectedAt(collectedDateKst.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime());
    }

    private QuoteSnapshot quoteCollectedAt(OffsetDateTime collectedAt) {
        QuoteSnapshot mockQuote = org.mockito.Mockito.mock(QuoteSnapshot.class);
        lenient().when(mockQuote.getCollectedAt()).thenReturn(collectedAt);
        return mockQuote;
    }

    private Candle candle(LocalDate date, String close) {
        return new Candle(
                date.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal("1000"), "KRW");
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> anyList() {
        return any(List.class);
    }
}
