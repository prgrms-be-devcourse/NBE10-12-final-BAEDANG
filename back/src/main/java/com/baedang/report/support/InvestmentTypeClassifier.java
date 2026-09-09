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
 * <p>모든 축은 <b>평가금액 가중 비중</b>을 컷오프로 가른다. 컷오프는 초안값이며, 실제 분포를
 * 본 뒤 튜닝한다(설계문서 §4.1, Phase 3). 지금은 상수로 두고, 추후 설정값으로 뺀다.
 *
 * <p><b>Axis 4(공격/안정)는 Phase 1 프록시</b> — 레버리지·인버스 비중만 본다. 보유 종목의
 * 가격 변동성은 daily_candle 이 필요해 후속으로 미룬다(설계문서 §4). 그래서 변동성 큰
 * 개별주만 담은 계좌도 "안정"으로 읽힐 수 있음(의도된 한계).
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

    /** 분류기 입력 한 건 — 보유 종목의 원화 평가금액과 분류에 필요한 종목 속성. */
    public record HoldingSlice(
            BigDecimal evalWon,
            StockCategory category,
            MarketCountry market,
            BigDecimal leverageFactor
    ) {
    }

    public InvestmentProfile classify(List<HoldingSlice> slices) {
        int holdingCount = slices.size();
        BigDecimal total = sum(slices, s -> true);
        if (holdingCount < MIN_HOLDINGS_FOR_CLASSIFICATION || total.signum() <= 0) {
            return InvestmentProfile.unclassified(holdingCount);
        }

        BigDecimal domesticShare = share(sum(slices, s -> s.market() == MarketCountry.KR), total);
        BigDecimal individualShare = share(sum(slices, InvestmentTypeClassifier::isIndividual), total);
        BigDecimal aggressiveShare = share(sum(slices, InvestmentTypeClassifier::isAggressive), total);
        BigDecimal top1Share = share(maxEval(slices), total);

        InvestmentType type = new InvestmentType(
                top1Share.compareTo(CONCENTRATION_TOP1_CUTOFF) >= 0
                        ? InvestmentType.Diversification.CONCENTRATED
                        : InvestmentType.Diversification.DIVERSIFIED,
                domesticShare.compareTo(DOMESTIC_CUTOFF) >= 0
                        ? InvestmentType.Market.DOMESTIC
                        : InvestmentType.Market.GLOBAL,
                individualShare.compareTo(INDIVIDUAL_CUTOFF) >= 0
                        ? InvestmentType.Instrument.INDIVIDUAL
                        : InvestmentType.Instrument.FUND,
                aggressiveShare.compareTo(AGGRESSIVE_CUTOFF) >= 0
                        ? InvestmentType.Risk.AGGRESSIVE
                        : InvestmentType.Risk.STABLE
        );
        return new InvestmentProfile(
                true, type, domesticShare, individualShare, top1Share, aggressiveShare, holdingCount);
    }

    /** 개별주 축의 개별주(S) 쪽 = 개별주 + 우선주. ETF·ETN 은 펀드(E) 쪽. */
    private static boolean isIndividual(HoldingSlice s) {
        return s.category() == StockCategory.INDIVIDUAL || s.category() == StockCategory.PREFERRED;
    }

    /** 공격 = 레버리지(배율 2 이상) 또는 인버스(음수). 일반 ETF(1.0)·일반주(null)는 제외. */
    private static boolean isAggressive(HoldingSlice s) {
        BigDecimal lf = s.leverageFactor();
        return lf != null && (lf.abs().compareTo(BigDecimal.valueOf(2)) >= 0 || lf.signum() < 0);
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

    private static BigDecimal share(BigDecimal part, BigDecimal total) {
        return part.divide(total, SHARE_SCALE, RoundingMode.HALF_UP);
    }
}
