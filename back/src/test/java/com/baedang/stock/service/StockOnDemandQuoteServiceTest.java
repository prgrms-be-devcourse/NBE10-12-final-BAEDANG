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
import com.baedang.market.service.QuoteSnapshotPersistenceService;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link StockOnDemandQuoteService} 단위 테스트 — 이슈 #75.
 *
 * <p>랭킹 상위 100(is_ranked=true) 종목은 항상 그대로 통과(no-op)해야 하고,
 * 랭킹 밖 종목만 "시세는 하루 한 번, 일봉은 비어 있을 때만" 온디맨드로 채워야 한다.
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
                Clock.fixed(NOW, ZoneOffset.UTC));
        // 테스트마다 실제로 쓰는 stub 조합이 달라서(예: 랭킹 안 종목 조기 반환 경로는
        // symbol/currency를 아예 안 읽는다) 공용 stub은 lenient로 둔다.
        lenient().when(stock.getStockId()).thenReturn(10L);
        lenient().when(stock.getSymbol()).thenReturn("005930");
        lenient().when(stock.getCurrency()).thenReturn("KRW");
        lenient().when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
    }

    @Test
    void 랭킹_상위_100_종목은_그대로_통과한다() {
        when(stock.getIsRanked()).thenReturn(true);
        QuoteSnapshot existing = quote(LocalDate.of(2020, 1, 1));

        QuoteSnapshot result = service.ensureQuote(stock, existing);

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(marketDataPort, quoteSnapshotPersistenceService, dailyCandleRepository, dailyCandlePersistenceService);
    }

    @Test
    void 랭킹_밖_종목이면서_시세가_없으면_온디맨드로_채운다() {
        when(stock.getIsRanked()).thenReturn(false);
        Candle dailyCandle = candle(LocalDate.of(2026, 8, 28), "236050"); // 어제(평일) 종가
        when(marketDataPort.fetchCandles("005930", CandleInterval.ONE_DAY, 5))
                .thenReturn(List.of(dailyCandle));
        when(marketDataPort.fetchPrices(List.of("005930")))
                .thenReturn(List.of(new PriceQuote("005930", new BigDecimal("241500"), OffsetDateTime.now(), "KRW")));

        QuoteSnapshot refreshed = quote(LocalDate.of(2026, 8, 28));
        when(quoteSnapshotRepository.findById(10L)).thenReturn(Optional.of(refreshed));
        // 처음(ensureDailyCandles의 존재 확인)엔 일봉이 하나도 없다가, 백필 이후
        // prevClose를 계산할 때(derivePrevClose)는 방금 채운 일봉이 보여야 한다.
        when(dailyCandleRepository.findByStockIdOrderByTradeDateDesc(eq(10L), any()))
                .thenReturn(List.of())
                .thenReturn(List.of(new DailyCandle(10L, LocalDate.of(2026, 8, 28),
                        new BigDecimal("236050"), new BigDecimal("236050"),
                        new BigDecimal("236050"), new BigDecimal("236050"), new BigDecimal("1000"))));

        QuoteSnapshot result = service.ensureQuote(stock, null);

        verify(dailyCandlePersistenceService).upsert(10L, "KRW", List.of(dailyCandle));
        verify(quoteSnapshotPersistenceService).saveOrUpdate(eq(List.of(stock)), anyList(), any());
        assertThat(result).isSameAs(refreshed);
        verify(refreshed).updatePrevClose(new BigDecimal("236050"));
    }

    @Test
    void 시세가_오늘_이미_수집됐으면_다시_조회하지_않는다() {
        when(stock.getIsRanked()).thenReturn(false);
        when(dailyCandleRepository.findByStockIdOrderByTradeDateDesc(eq(10L), any()))
                .thenReturn(List.of(new DailyCandle(10L, LocalDate.of(2026, 8, 28),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
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
        when(dailyCandleRepository.findByStockIdOrderByTradeDateDesc(eq(10L), any()))
                .thenReturn(List.of(new DailyCandle(10L, LocalDate.of(2026, 8, 28),
                        new BigDecimal("100"), new BigDecimal("100"),
                        new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ONE)));
        QuoteSnapshot yesterdayQuote = quoteCollectedAt(OffsetDateTime.parse("2026-08-30T10:00:00+09:00"));
        when(marketDataPort.fetchPrices(List.of("005930")))
                .thenReturn(List.of(new PriceQuote("005930", new BigDecimal("105"), OffsetDateTime.now(), "KRW")));
        when(quoteSnapshotRepository.findById(10L)).thenReturn(Optional.of(yesterdayQuote));

        service.ensureQuote(stock, yesterdayQuote);

        verify(marketDataPort).fetchPrices(List.of("005930"));
        verify(quoteSnapshotPersistenceService).saveOrUpdate(eq(List.of(stock)), anyList(), any());
    }

    @Test
    void 일봉이_이미_있으면_백필하지_않는다() {
        when(stock.getIsRanked()).thenReturn(false);
        when(dailyCandleRepository.findByStockIdOrderByTradeDateDesc(eq(10L), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(new DailyCandle(10L, LocalDate.of(2026, 8, 28),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));

        service.ensureDailyCandles(stock);

        verify(marketDataPort, never()).fetchCandles(any(), any(), anyInt());
        verifyNoInteractions(dailyCandlePersistenceService);
    }

    @Test
    void 랭킹_상위_100_종목은_일봉_확인조차_하지_않는다() {
        when(stock.getIsRanked()).thenReturn(true);

        service.ensureDailyCandles(stock);

        verifyNoInteractions(dailyCandleRepository, marketDataPort, dailyCandlePersistenceService);
    }

    @Test
    void 시세_조회가_실패해도_예외를_던지지_않고_기존_값을_돌려준다() {
        when(stock.getIsRanked()).thenReturn(false);
        when(dailyCandleRepository.findByStockIdOrderByTradeDateDesc(eq(10L), any()))
                .thenReturn(List.of(new DailyCandle(10L, LocalDate.of(2026, 8, 28),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
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
