package com.baedang.report.support;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.CostReplayEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class FourWeekCostProfilerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-09T00:00:00Z");
    private static final OffsetDateTime WINDOW_START = NOW.minusWeeks(4);

    private final FourWeekCostProfiler profiler = new FourWeekCostProfiler(new InvestmentTypeClassifier());

    private static Stock stock(StockCategory category, MarketCountry market, String leverage) {
        Stock s = mock(Stock.class);
        lenient().when(s.getStockCategory()).thenReturn(category);
        lenient().when(s.getMarketCountry()).thenReturn(market);
        lenient().when(s.getLeverageFactor()).thenReturn(leverage == null ? null : new BigDecimal(leverage));
        return s;
    }

    private static CostReplayEvent buy(long stockId, long grossKrw, String at) {
        return new CostReplayEvent(stockId, OrderSide.BUY, BigDecimal.TEN,
                BigDecimal.valueOf(grossKrw), OffsetDateTime.parse(at));
    }

    private Map<Long, Stock> universe() {
        return Map.of(
                1L, stock(StockCategory.INDIVIDUAL, MarketCountry.KR, null),
                2L, stock(StockCategory.ETF, MarketCountry.US, "1.0"));
    }

    @Test
    void 창_이전에_모두_매수해_구성이_불변이면_평균은_스냅샷과_같다() {
        List<CostReplayEvent> events = List.of(
                buy(1, 10_000_000, "2026-07-29T00:00:00Z"),  // 창(08-12) 이전
                buy(2, 10_000_000, "2026-07-29T00:00:00Z"));

        AxisShares avg = profiler.averageShares(events, universe(), WINDOW_START, NOW);

        // 10:10 → 국내·개별주 비중 0.5 로 창 내내 불변.
        assertThat(avg.domesticShare()).isEqualByComparingTo("0.5");
        assertThat(avg.individualShare()).isEqualByComparingTo("0.5");
    }

    @Test
    void 종목이_창_중간에_편입되면_평균이_스냅샷과_달라진다() {
        List<CostReplayEvent> events = List.of(
                buy(1, 10_000_000, "2026-07-29T00:00:00Z"),  // 국내, 창 이전부터 보유
                buy(2, 10_000_000, "2026-08-26T00:00:00Z")); // 미국, 창 중간(2주차) 편입

        AxisShares avg = profiler.averageShares(events, universe(), WINDOW_START, NOW);

        // 스냅샷(현재) 국내 비중 = 0.5 지만, 창 초반엔 국내 100% 라 평균은 0.5 보다 크다.
        assertThat(avg.domesticShare()).isGreaterThan(new BigDecimal("0.5"));
        assertThat(avg.domesticShare()).isLessThan(BigDecimal.ONE);
    }

    @Test
    void 창_안에_보유가_전혀_없으면_null() {
        assertThat(profiler.averageShares(List.of(), Map.of(), WINDOW_START, NOW)).isNull();
    }

    @Test
    void 전량_매도하면_그_뒤_시점의_원가에서_빠진다() {
        // 국내 10M(창 이전) + 미국 10M(창 이전) 보유하다, 미국을 창 중간에 전량 매도.
        List<CostReplayEvent> events = List.of(
                buy(1, 10_000_000, "2026-07-29T00:00:00Z"),
                buy(2, 10_000_000, "2026-07-29T00:00:00Z"),
                new CostReplayEvent(2L, OrderSide.SELL, BigDecimal.TEN, BigDecimal.ZERO,
                        OffsetDateTime.parse("2026-08-26T00:00:00Z")));

        AxisShares avg = profiler.averageShares(events, universe(), WINDOW_START, NOW);

        // 매도 후 국내만 남아 국내 100% 구간이 생기므로 평균 국내 비중 > 0.5.
        assertThat(avg.domesticShare()).isGreaterThan(new BigDecimal("0.5"));
    }
}
