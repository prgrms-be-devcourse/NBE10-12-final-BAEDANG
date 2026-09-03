package com.baedang.stock.service;

import com.baedang.market.repository.QuoteSnapshotBatchRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.RankingEntry;
import com.baedang.stock.port.RankingPort;
import com.baedang.stock.port.RankingSnapshot;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StockRankingLoadServiceTest {

    private final RankingPort rankingPort = mock(RankingPort.class);
    private final StockRepository stockRepository = mock(StockRepository.class);

    /** {@code applyRanking} 은 프록시 경유(@Transactional)라 자기 자신을 주입받는다. */
    private final StockRankingLoadService self = mock(StockRankingLoadService.class);

    private final QuoteSnapshotBatchRepository quoteSnapshotBatchRepository =
            mock(QuoteSnapshotBatchRepository.class);

    private static final OffsetDateTime RANKED_AT =
            OffsetDateTime.parse("2026-09-07T08:00:00+09:00");

    /** collected_at 을 고정해 검증할 수 있도록 TimeConfig 의 Clock 을 대체한다. */
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-07T08:00:05Z");
    private final Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private final StockRankingLoadService service = new StockRankingLoadService(
            rankingPort, stockRepository, quoteSnapshotBatchRepository, clock, self);

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
