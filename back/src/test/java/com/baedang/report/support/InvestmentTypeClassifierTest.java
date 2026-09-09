package com.baedang.report.support;

import com.baedang.report.support.InvestmentTypeClassifier.HoldingSlice;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentTypeClassifierTest {

    private final InvestmentTypeClassifier classifier = new InvestmentTypeClassifier();

    private static HoldingSlice slice(long evalWon, StockCategory category, MarketCountry market, String leverage) {
        return new HoldingSlice(
                BigDecimal.valueOf(evalWon),
                category,
                market,
                leverage == null ? null : new BigDecimal(leverage));
    }

    @Test
    void 보유_종목이_2개_미만이면_미분류() {
        assertThat(classifier.classify(List.of()).classified()).isFalse();
        assertThat(classifier.classify(List.of(
                slice(1000, StockCategory.INDIVIDUAL, MarketCountry.KR, null))).classified()).isFalse();
    }

    @Test
    void 평가금액_합이_0이면_미분류() {
        InvestmentProfile p = classifier.classify(List.of(
                slice(0, StockCategory.INDIVIDUAL, MarketCountry.KR, null),
                slice(0, StockCategory.ETF, MarketCountry.US, "1.0")));
        assertThat(p.classified()).isFalse();
    }

    @Test
    void 집중_국내_개별주_공격형_CKSA() {
        // KR 개별주 700원(지배적) + KR 레버리지ETF 300원 → 집중·국내·개별주·공격
        InvestmentProfile p = classifier.classify(List.of(
                slice(700, StockCategory.INDIVIDUAL, MarketCountry.KR, null),
                slice(300, StockCategory.ETF, MarketCountry.KR, "2.0")));

        assertThat(p.classified()).isTrue();
        assertThat(p.type().code()).isEqualTo("CKSA");
        assertThat(p.top1Share()).isEqualByComparingTo("0.70");
        assertThat(p.domesticShare()).isEqualByComparingTo("1.00");
        assertThat(p.individualShare()).isEqualByComparingTo("0.70");
        assertThat(p.aggressiveShare()).isEqualByComparingTo("0.30");
    }

    @Test
    void 분산_해외_ETF_안정형_DGEB() {
        // US ETF/ETN 3종 고르게 → 분산·해외·ETF·안정
        InvestmentProfile p = classifier.classify(List.of(
                slice(400, StockCategory.ETF, MarketCountry.US, "1.0"),
                slice(350, StockCategory.ETF, MarketCountry.US, "1.0"),
                slice(250, StockCategory.ETN, MarketCountry.US, null)));

        assertThat(p.type().code()).isEqualTo("DGEB");
        assertThat(p.top1Share()).isEqualByComparingTo("0.40");   // 최대 40% < 50% → 분산
        assertThat(p.aggressiveShare()).isEqualByComparingTo("0"); // 일반 ETF·ETN 은 공격 아님
    }

    @Test
    void 우선주는_개별주쪽_ETN은_펀드쪽으로_집계() {
        InvestmentProfile p = classifier.classify(List.of(
                slice(600, StockCategory.PREFERRED, MarketCountry.KR, null),
                slice(400, StockCategory.ETN, MarketCountry.KR, null)));
        assertThat(p.individualShare()).isEqualByComparingTo("0.60"); // 우선주=개별주쪽
        assertThat(p.type().instrument()).isEqualTo(InvestmentType.Instrument.INDIVIDUAL);
    }

    @Test
    void 인버스는_공격으로_집계_일반ETF는_아님() {
        InvestmentProfile p = classifier.classify(List.of(
                slice(500, StockCategory.ETF, MarketCountry.US, "-1.0"), // 인버스 → 공격
                slice(500, StockCategory.ETF, MarketCountry.US, "1.0")));  // 일반 ETF → 아님
        assertThat(p.aggressiveShare()).isEqualByComparingTo("0.50");
        assertThat(p.type().risk()).isEqualTo(InvestmentType.Risk.AGGRESSIVE);
    }

    @Test
    void 컷오프_경계_50퍼센트는_국내_개별주_집중쪽() {
        // 정확히 50%면 K·S·집중 쪽으로 붙는다(>= 규약).
        InvestmentProfile p = classifier.classify(List.of(
                slice(500, StockCategory.INDIVIDUAL, MarketCountry.KR, null),
                slice(500, StockCategory.ETF, MarketCountry.US, "1.0")));
        assertThat(p.type().market()).isEqualTo(InvestmentType.Market.DOMESTIC);
        assertThat(p.type().instrument()).isEqualTo(InvestmentType.Instrument.INDIVIDUAL);
        assertThat(p.type().diversification()).isEqualTo(InvestmentType.Diversification.CONCENTRATED);
    }
}
