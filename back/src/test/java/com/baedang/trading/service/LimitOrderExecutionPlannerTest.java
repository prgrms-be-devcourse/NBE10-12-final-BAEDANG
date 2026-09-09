package com.baedang.trading.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.CumulativeSettlementState;
import com.baedang.trading.model.LimitExecutionPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LimitOrderExecutionPlannerTest {
    private final LimitOrderSettlementCalculator calculator = new LimitOrderSettlementCalculator(
            n("0.0001"), n("0.002"), n("0.0000206"), n("0.01"), n("1000000"));
    private final LimitOrderExecutionPlanner planner = new LimitOrderExecutionPlanner(calculator);

    @Test
    void 여러_호가의_누적_차액을_계산하고_완료시_동결_잔액을_해제한다() {
        LimitExecutionPlan plan = buy("3", "420042", "1400", List.of(level(1,"99","1"),level(2,"100","9")));
        assertThat(plan.fills()).hasSize(2);
        assertThat(plan.nextState().grossAmountKrw()).isEqualByComparingTo("418600");
        assertThat(plan.nextState().feeKrw()).isEqualByComparingTo("42");
        assertThat(plan.releasedCash()).isEqualByComparingTo("1400");
        assertThat(plan.remainingReservedCash()).isZero();
        assertThat(plan.remainingQuantity()).isZero();
    }

    @Test
    void 환율상승은_자유예수금없이_동결안에서_최대_정수수량으로_줄인다() {
        LimitExecutionPlan plan = buy("3", "420042", "1700", List.of(level(1,"100","9")));
        assertThat(plan.fills()).singleElement().satisfies(fill -> assertThat(fill.quantity()).isEqualByComparingTo("2"));
        assertThat(plan.remainingReservedCash()).isEqualByComparingTo("80008");
        assertThat(plan.remainingQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void 부분체결의_동결0원은_금지하지만_전량의_정확한_소진은_허용한다() {
        assertThat(buy("2", "140014", "1400", List.of(level(1,"100","2"))).fills()).isEmpty();
        assertThat(buy("1", "140014", "1400", List.of(level(1,"100","2"))).remainingQuantity()).isZero();
    }

    @Test
    void 미국_매도_SEC최소액은_호가별로_반복부과하지_않는다() {
        LimitExecutionPlan plan = planner.plan(MarketCountry.US, OrderSide.SELL, n("90"), n("2"), BigDecimal.ZERO,
                n("1400"), CumulativeSettlementState.empty(), List.of(level(1,"100","1"),level(2,"99","1")));
        assertThat(plan.fills()).hasSize(2);
        assertThat(plan.fills().getFirst().amounts().secFeeUsd()).isEqualByComparingTo("0.01");
        assertThat(plan.fills().getLast().amounts().secFeeUsd()).isZero();
        assertThat(plan.nextState().taxKrw()).isEqualByComparingTo("14");
    }

    @Test
    void 저가_매도_첫호가의_순금액0은_다음호가와_합치지_않는다() {
        LimitExecutionPlan plan = planner.plan(MarketCountry.US, OrderSide.SELL, n("0.01"), n("2"), BigDecimal.ZERO,
                n("1400"), CumulativeSettlementState.empty(), List.of(level(1,"0.01","1")));
        assertThat(plan.fills()).isEmpty();
        assertThat(plan.reason()).isEqualTo(LimitExecutionPlan.StopReason.NON_POSITIVE_SETTLEMENT);
    }

    @Test
    void 소진호가는_건너뛰되_지정가_범위를_넘는_호가는_소비하지_않는다() {
        LimitExecutionPlan plan = buy("3", "420042", "1400", List.of(level(1,"99","0"),level(2,"101","10")));
        assertThat(plan.fills()).isEmpty();
        assertThat(plan.reason()).isEqualTo(LimitExecutionPlan.StopReason.PRICE_LIMIT);
    }

    @Test
    void 표시용_평균을_센트로_반올림해도_정산합계는_그대로다() {
        LimitExecutionPlan plan = buy("3", "420042", "1400", List.of(level(1,"99","1"),level(2,"100","2")));
        java.time.Instant now = java.time.Instant.parse("2026-09-09T01:00:00Z");
        com.baedang.trading.dto.LimitExecutionPreviewResponse response = com.baedang.trading.dto.LimitExecutionPreviewResponse.from(
                new com.baedang.trading.model.LimitExecutionBook(1L,0L,now,now,List.of()),plan,2,now);
        assertThat(response.avgExecutionPrice()).isEqualTo("99.67");
        assertThat(response.grossAmountKrw()).isEqualTo("418600");
    }

    private LimitExecutionPlan buy(String quantity, String cash, String rate, List<LimitExecutionPlan.Level> levels) {
        return planner.plan(MarketCountry.US, OrderSide.BUY, n("100"), n(quantity), n(cash), n(rate), CumulativeSettlementState.empty(), levels);
    }

    @Test
    void 최대후보가_저장상한을_넘어도_저장가능한_정수수량을_찾는다() {
        LimitExecutionPlan plan = planner.plan(MarketCountry.US,OrderSide.BUY,n("100000000000000"),n("1000"),
                n("999999999999999"),BigDecimal.ONE,CumulativeSettlementState.empty(),List.of(level(1,"100000000000000","1000")));
        assertThat(plan.fills()).singleElement().satisfies(fill -> assertThat(fill.quantity()).isEqualByComparingTo("9"));
    }

    @Test
    void 뒤호가가_원화0으로_반올림되면_앞선_양수체결은_유지한다() {
        LimitExecutionPlan plan = planner.plan(MarketCountry.US,OrderSide.SELL,n("0.01"),n("2"),BigDecimal.ZERO,
                BigDecimal.ONE,CumulativeSettlementState.empty(),List.of(level(1,"100","1"),level(2,"0.01","1")));
        assertThat(plan.fills()).hasSize(1);
        assertThat(plan.reason()).isEqualTo(LimitExecutionPlan.StopReason.NON_POSITIVE_SETTLEMENT);
        assertThat(plan.remainingQuantity()).isEqualByComparingTo("1");
    }
    private static LimitExecutionPlan.Level level(long id,String price,String quantity) { return new LimitExecutionPlan.Level(id,n(price),n(quantity)); }
    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
