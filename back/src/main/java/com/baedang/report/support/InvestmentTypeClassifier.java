package com.baedang.report.support;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockCategory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 보유 종목을 투자 성향 MBTI 4축으로 분류하는 순수 계산기(DB·시각 비의존).
 *
 * <p>각 축은 <b>비중</b>을 컷오프로 가른다. 라이브 경로(§6.2)는 4주 창의 <b>원가(cost) 구성</b>을
 * 시점별로 복원해 축 비중을 뽑고({@link #shares}), 그 <b>일별 비중을 평균</b>낸 값으로 판정한다
 * ({@link #classifyFromShares}). 판정은 정밀 비중 그대로 하고 반올림은 표시용({@link #SHARE_SCALE})
 * 으로만 한다(경계값이 반올림 때문에 유형을 넘나들지 않게, fix ②). 컷오프는 시딩 분포로 검증했다
 * (2026-09-11: 개별주 0.50 유지 확정).
 *
 * <p><b>Axis 4(공격/안정)는 프록시</b> — 레버리지·인버스 비중만 본다. 보유 종목의 가격 변동성은
 * daily_candle 이 필요해 후속으로 미룬다(설계문서 §4). 그래서 변동성 큰 개별주만 담은 계좌도
 * "안정"으로 읽힐 수 있음(의도된 한계).
 */
@Component
public class InvestmentTypeClassifier {

    /** 유형을 정하려면 최소 이만큼의 보유 종목이 필요하다. 미만이면 "미분류/신규". */
    static final int MIN_HOLDINGS_FOR_CLASSIFICATION = 2;

    /** 최대 비중 한 종목이 이 이상이면 집중형. */
    static final BigDecimal CONCENTRATION_TOP1_CUTOFF = new BigDecimal("0.50");
    /** 국내 평가비중이 이 이상이면 국내형. */
    static final BigDecimal DOMESTIC_CUTOFF = new BigDecimal("0.50");
    /** 개별주(개별주·우선주) 평가비중이 이 이상이면 개별주형. */
    static final BigDecimal INDIVIDUAL_CUTOFF = new BigDecimal("0.50");
    /** 레버리지·인버스 평가비중이 이 이상이면 공격형. */
    static final BigDecimal AGGRESSIVE_CUTOFF = new BigDecimal("0.20");

    private static final int SHARE_SCALE = 4;
    /** 일별 비중을 평균낼 때는 반올림 누적 오차를 줄이려 높은 정밀도로 계산한다. */
    private static final int TIMELINE_SHARE_SCALE = 10;

    /** 분류기 입력 한 건 — 보유 종목의 원화 평가금액과 분류에 필요한 종목 속성. */
    public record HoldingSlice(
            BigDecimal evalWon,
            StockCategory category,
            MarketCountry market,
            BigDecimal leverageFactor
    ) {
    }

    /**
     * 한 시점의 슬라이스로 4축 비중만 계산한다(분류 판정은 하지 않음). 원가 4주 평균(§6.2)이
     * 일별 비중을 뽑아 평균낼 때 쓴다. 평가액(=그 시점 원가)이 없으면 {@code null}.
     * 평균 누적 오차를 줄이려 높은 정밀도({@link #TIMELINE_SHARE_SCALE})로 나눈다.
     */
    public AxisShares shares(List<HoldingSlice> slices) {
        BigDecimal total = sum(slices, s -> true);
        if (total.signum() <= 0) {
            return null;
        }
        return new AxisShares(
                timelineShare(sum(slices, s -> s.market() == MarketCountry.KR), total),
                timelineShare(sum(slices, InvestmentTypeClassifier::isIndividual), total),
                timelineShare(maxEval(slices), total),
                timelineShare(sum(slices, InvestmentTypeClassifier::isAggressive), total));
    }

    /**
     * 이미 계산된(예: 4주 평균) 비중으로 유형을 판정한다. 비중이 곧 판정 지표이므로 컷오프와
     * 직접 비교한다({@code 비중 >= 컷오프}). <b>판정은 넘어온 정밀 비중 그대로</b> 하고, 반올림은
     * 표시용({@link #SHARE_SCALE}자리)으로만 한다 — 경계값(예 0.49996)이 반올림 때문에 유형을
     * 넘나들지 않게(설계문서 fix ②). 종목 수가 부족하면 유형을 정하지 않되 비중은 담는다.
     */
    public InvestmentProfile classifyFromShares(AxisShares shares, int holdingCount) {
        if (shares == null) {
            return InvestmentProfile.unclassified(holdingCount);
        }
        BigDecimal domestic = shares.domesticShare();
        BigDecimal individual = shares.individualShare();
        BigDecimal top1 = shares.top1Share();
        BigDecimal aggressive = shares.aggressiveShare();
        InvestmentType type = holdingCount < MIN_HOLDINGS_FOR_CLASSIFICATION ? null : new InvestmentType(
                top1.compareTo(CONCENTRATION_TOP1_CUTOFF) >= 0
                        ? InvestmentType.Diversification.CONCENTRATED : InvestmentType.Diversification.DIVERSIFIED,
                domestic.compareTo(DOMESTIC_CUTOFF) >= 0
                        ? InvestmentType.Market.DOMESTIC : InvestmentType.Market.GLOBAL,
                individual.compareTo(INDIVIDUAL_CUTOFF) >= 0
                        ? InvestmentType.Instrument.INDIVIDUAL : InvestmentType.Instrument.FUND,
                aggressive.compareTo(AGGRESSIVE_CUTOFF) >= 0
                        ? InvestmentType.Risk.AGGRESSIVE : InvestmentType.Risk.STABLE);
        // 표시용 반올림(판정과 분리).
        return new InvestmentProfile(type != null, type,
                display(domestic), display(individual), display(top1), display(aggressive), holdingCount);
    }

    private static BigDecimal display(BigDecimal share) {
        return share.setScale(SHARE_SCALE, RoundingMode.HALF_UP);
    }

    /** 개별주 축의 개별주(S) 쪽 = 개별주 + 우선주. ETF·ETN 은 펀드(E) 쪽. */
    public static boolean isIndividual(HoldingSlice s) {
        return s.category() == StockCategory.INDIVIDUAL || s.category() == StockCategory.PREFERRED;
    }

    /** 공격 = 레버리지(배율 2 이상) 또는 인버스(음수). 일반 ETF(1.0)·일반주(null)는 제외. */
    public static boolean isAggressive(HoldingSlice s) {
        BigDecimal lf = s.leverageFactor();
        return lf != null && (lf.abs().compareTo(BigDecimal.valueOf(2)) >= 0 || lf.signum() < 0);
    }

    private static BigDecimal timelineShare(BigDecimal part, BigDecimal total) {
        return part.divide(total, TIMELINE_SHARE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal maxEval(List<HoldingSlice> slices) {
        return slices.stream()
                .map(s -> s.evalWon() == null ? BigDecimal.ZERO : s.evalWon())
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private static BigDecimal sum(List<HoldingSlice> slices, java.util.function.Predicate<HoldingSlice> filter) {
        return slices.stream()
                .filter(filter)
                .map(s -> s.evalWon() == null ? BigDecimal.ZERO : s.evalWon())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
