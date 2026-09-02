package com.baedang.stock.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.RankingEntry;
import com.baedang.stock.port.RankingPort;
import com.baedang.stock.port.RankingSnapshot;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class StockRankingLoadServiceTest {

    private final RankingPort rankingPort = mock(RankingPort.class);
    private final StockRepository stockRepository = mock(StockRepository.class);

    /** {@code applyRanking} 은 프록시 경유(@Transactional)라 자기 자신을 주입받는다. */
    private final StockRankingLoadService self = mock(StockRankingLoadService.class);

    private final QuoteSnapshotRepository quoteSnapshotRepository =
            mock(QuoteSnapshotRepository.class);

    private final StockRankingLoadService service = new StockRankingLoadService(
            rankingPort, stockRepository, quoteSnapshotRepository, self);

    private static final OffsetDateTime RANKED_AT =
            OffsetDateTime.parse("2026-09-07T08:00:00+09:00");

    @Nested
    class Load {

        @Test
        void 지정한_시장만_적재한다() {
            List<RankingEntry> entries = List.of(엔트리(1, "005930"));
            when(rankingPort.fetchRanking(MarketCountry.KR))
                    .thenReturn(new RankingSnapshot(entries, RANKED_AT));

            service.load(MarketCountry.KR);

            verify(rankingPort).fetchRanking(MarketCountry.KR);
            verifyNoMoreInteractions(rankingPort);
            verify(self).applyRanking(MarketCountry.KR, entries, RANKED_AT);
        }

        @Test
        void 집계가_비면_직전_유니버스를_유지한다() {
            when(rankingPort.fetchRanking(MarketCountry.US))
                    .thenReturn(new RankingSnapshot(List.of(), null));

            service.load(MarketCountry.US);

            // 지우고 다시 쓰는 경로에 진입하면 유니버스가 통째로 비어 전 종목 주문이 막힌다.
            verifyNoInteractions(self);
            verifyNoInteractions(stockRepository);
        }

        @Test
        void loadAll_은_두_시장을_모두_적재한다() {
            List<RankingEntry> kr = List.of(엔트리(1, "005930"));
            List<RankingEntry> us = List.of(엔트리(1, "AAPL"));
            when(rankingPort.fetchRanking(MarketCountry.KR))
                    .thenReturn(new RankingSnapshot(kr, RANKED_AT));
            when(rankingPort.fetchRanking(MarketCountry.US))
                    .thenReturn(new RankingSnapshot(us, RANKED_AT));

            service.loadAll();

            verify(self).applyRanking(MarketCountry.KR, kr, RANKED_AT);
            verify(self).applyRanking(MarketCountry.US, us, RANKED_AT);
        }
    }

    @Nested
    class ApplyRanking {

        @Test
        void 새_랭킹에_없는_종목은_빠진다() {
            Stock 유지 = 랭킹_종목("005930", 1);
            Stock 탈락 = 랭킹_종목("000660", 2);
            직전_유니버스(MarketCountry.KR, 유지, 탈락);
            심볼_조회(유지);

            service.applyRanking(MarketCountry.KR, List.of(엔트리(1, "005930")), RANKED_AT);

            assertThat(유지.getIsRanked()).isTrue();
            assertThat(탈락.getIsRanked()).isFalse();
            assertThat(탈락.getRankNo()).isNull();
        }

        @Test
        void 순위와_거래대금을_반영한다() {
            Stock 종목 = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
            직전_유니버스(MarketCountry.KR);
            심볼_조회(종목);

            service.applyRanking(
                    MarketCountry.KR,
                    List.of(엔트리(3, "005930", new BigDecimal("70000000000"))),
                    RANKED_AT);

            assertThat(종목.getIsRanked()).isTrue();
            assertThat(종목.getRankNo()).isEqualTo(3);
            assertThat(종목.getTradingAmount()).isEqualByComparingTo("70000000000");
        }

        @Test
        void 없는_심볼은_건너뛰고_나머지는_적재한다() {
            Stock 존재 = Stock.create("005930", MarketCountry.KR, "KOSPI", "삼성전자", null, "KRW", "STOCK", true);
            직전_유니버스(MarketCountry.KR);
            심볼_조회(존재);

            service.applyRanking(
                    MarketCountry.KR,
                    List.of(엔트리(1, "999999"), 엔트리(2, "005930")),
                    RANKED_AT);

            assertThat(존재.getIsRanked()).isTrue();
            assertThat(존재.getRankNo()).isEqualTo(2);
        }

        @Test
        void 심볼을_정규화해_매칭한다() {
            Stock 애플 = Stock.create("AAPL", MarketCountry.US, "NASDAQ", "애플", null, "USD", "STOCK", true);
            직전_유니버스(MarketCountry.US);
            심볼_조회(애플);

            service.applyRanking(MarketCountry.US, List.of(엔트리(1, " aapl ")), RANKED_AT);

            assertThat(애플.getIsRanked()).isTrue();
            assertThat(애플.getRankNo()).isEqualTo(1);
        }
    }

    @Nested
    class 신규_편입_시세_초기화 {

        @Test
        void 스냅샷이_없으면_만들고_prev_close를_basePrice로_채운다() {
            직전_유니버스(MarketCountry.KR);
            심볼_조회(종목("005930", 1L));
            스냅샷_조회();

            service.applyRanking(MarketCountry.KR, List.of(엔트리(1, "005930")), RANKED_AT);

            QuoteSnapshot 생성 = 저장된_스냅샷();
            assertThat(생성.getStockId()).isEqualTo(1L);
            assertThat(생성.getLastPrice()).isEqualByComparingTo("70000");
            assertThat(생성.getPrevClose()).isEqualByComparingTo("69000");
            assertThat(생성.getCurrency()).isEqualTo("KRW");
            assertThat(생성.getQuoteAt()).isEqualTo(RANKED_AT);
            // (70000 - 69000) / 69000
            assertThat(생성.changeRate()).isEqualByComparingTo("0.014493");
        }

        @Test
        void 스냅샷이_있으면_prev_close만_갱신하고_현재가는_두다() {
            직전_유니버스(MarketCountry.KR);
            심볼_조회(종목("005930", 1L));
            QuoteSnapshot 기존 = 스냅샷(1L, new BigDecimal("71000"));
            스냅샷_조회(기존);

            service.applyRanking(MarketCountry.KR, List.of(엔트리(1, "005930")), RANKED_AT);

            assertThat(기존.getPrevClose()).isEqualByComparingTo("69000");
            // 실시간 수집기가 관리하는 값이라 덮어쓰면 안 된다.
            assertThat(기존.getLastPrice()).isEqualByComparingTo("71000");
            verify(quoteSnapshotRepository, never()).save(any());
        }

        @Test
        void 지난주에도_랭킹이던_종목은_건드리지_않는다() {
            Stock 유지 = 종목("005930", 1L);
            유지.applyRanking(1, new BigDecimal("1000000000"));
            직전_유니버스(MarketCountry.KR, 유지);
            심볼_조회(유지);

            service.applyRanking(MarketCountry.KR, List.of(엔트리(1, "005930")), RANKED_AT);

            // prev_close 는 매일 08:50 배치가 이미 관리한다.
            verifyNoInteractions(quoteSnapshotRepository);
        }

        @Test
        void 조회는_종목마다_하지_않고_한_번에_한다() {
            직전_유니버스(MarketCountry.KR);
            심볼_조회(종목("005930", 1L), 종목("000660", 2L));
            스냅샷_조회();

            service.applyRanking(
                    MarketCountry.KR,
                    List.of(엔트리(1, "005930"), 엔트리(2, "000660")),
                    RANKED_AT);

            verify(quoteSnapshotRepository, times(1)).findByStockIdIn(any());
        }

        @Test
        void 통화가_다르면_랭킹은_넣되_시세는_건너뛴다() {
            Stock 신규 = Stock.create(
                    "AAPL", MarketCountry.US, "NASDAQ", "애플", null, "USD", "STOCK", true);
            ReflectionTestUtils.setField(신규, "stockId", 1L);
            직전_유니버스(MarketCountry.US);
            심볼_조회(신규);

            // 엔트리의 통화는 KRW 다.
            service.applyRanking(MarketCountry.US, List.of(엔트리(1, "AAPL")), RANKED_AT);

            assertThat(신규.getIsRanked()).isTrue();
            verifyNoInteractions(quoteSnapshotRepository);
        }

        @Test
        void 기준가가_0이면_prev_close를_비워둔다() {
            직전_유니버스(MarketCountry.KR);
            심볼_조회(종목("005930", 1L));
            스냅샷_조회();

            service.applyRanking(
                    MarketCountry.KR,
                    List.of(엔트리(1, "005930", new BigDecimal("70000"), BigDecimal.ZERO)),
                    RANKED_AT);

            // 등락률을 0% 로 속이지 않는다 — null 로 내보낸다.
            assertThat(저장된_스냅샷().getPrevClose()).isNull();
            assertThat(저장된_스냅샷().changeRate()).isNull();
        }

        @Test
        void 현재가가_없으면_스냅샷을_만들지_않고_랭킹만_반영한다() {
            Stock 종목 = 종목("005930", 1L);
            직전_유니버스(MarketCountry.KR);
            심볼_조회(종목);
            스냅샷_조회();

            service.applyRanking(
                    MarketCountry.KR,
                    List.of(엔트리(1, "005930", null, new BigDecimal("69000"))),
                    RANKED_AT);

            // 종목 하나의 시세 결측이 유니버스 전체를 막으면 안 된다.
            assertThat(종목.getIsRanked()).isTrue();
            verify(quoteSnapshotRepository, never()).save(any());
        }

        @Test
        void 집계_시각이_없으면_수집_시각으로_대체한다() {
            직전_유니버스(MarketCountry.KR);
            심볼_조회(종목("005930", 1L));
            스냅샷_조회();

            service.applyRanking(MarketCountry.KR, List.of(엔트리(1, "005930")), null);

            assertThat(저장된_스냅샷().getQuoteAt()).isNotNull();
        }

        private QuoteSnapshot 저장된_스냅샷() {
            ArgumentCaptor<QuoteSnapshot> captor = ArgumentCaptor.forClass(QuoteSnapshot.class);
            verify(quoteSnapshotRepository).save(captor.capture());
            return captor.getValue();
        }
    }

    private Stock 종목(String symbol, Long stockId) {
        Stock stock = Stock.create(
                symbol, MarketCountry.KR, "KOSPI", symbol, null, "KRW", "STOCK", true);
        ReflectionTestUtils.setField(stock, "stockId", stockId);
        return stock;
    }

    private QuoteSnapshot 스냅샷(Long stockId, BigDecimal lastPrice) {
        return new QuoteSnapshot(stockId, lastPrice, "KRW", RANKED_AT, RANKED_AT);
    }

    private void 스냅샷_조회(QuoteSnapshot... snapshots) {
        when(quoteSnapshotRepository.findByStockIdIn(any())).thenReturn(List.of(snapshots));
        when(quoteSnapshotRepository.save(any(QuoteSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void 직전_유니버스(MarketCountry marketCountry, Stock... stocks) {
        when(stockRepository.findByMarketCountryAndIsRankedTrue(marketCountry))
                .thenReturn(List.of(stocks));
    }

    private void 심볼_조회(Stock... stocks) {
        when(stockRepository.findByMarketCountryAndSymbolIn(any(MarketCountry.class), any()))
                .thenReturn(List.of(stocks));
    }

    private Stock 랭킹_종목(String symbol, int rankNo) {
        Stock stock = Stock.create(
                symbol, MarketCountry.KR, "KOSPI", symbol, null, "KRW", "STOCK", true);
        stock.applyRanking(rankNo, new BigDecimal("1000000000"));
        return stock;
    }

    private RankingEntry 엔트리(int rank, String symbol) {
        return 엔트리(rank, symbol, new BigDecimal("70000000000"));
    }

    private RankingEntry 엔트리(
            int rank, String symbol, BigDecimal lastPrice, BigDecimal basePrice) {
        return new RankingEntry(
                rank, symbol, "KRW", lastPrice, basePrice,
                new BigDecimal("0.0145"),
                new BigDecimal("1000000"),
                new BigDecimal("70000000000")
        );
    }

    private RankingEntry 엔트리(int rank, String symbol, BigDecimal tradingAmount) {
        return new RankingEntry(
                rank,
                symbol,
                "KRW",
                new BigDecimal("70000"),
                new BigDecimal("69000"),
                new BigDecimal("0.0145"),
                new BigDecimal("1000000"),
                tradingAmount
        );
    }
}
