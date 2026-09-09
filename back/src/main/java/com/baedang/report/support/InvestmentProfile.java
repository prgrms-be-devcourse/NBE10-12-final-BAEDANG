package com.baedang.report.support;

import java.math.BigDecimal;

/**
 * 투자 성향 분류 결과. 유형({@link InvestmentType})과 함께 각 축을 가른 근거 비중을 담아
 * 리포트가 "왜 이 유형인지"를 그대로 보여줄 수 있게 한다.
 *
 * <p>보유 종목이 부족하면({@code classified=false}) 유형을 정하지 않는다("미분류/신규").
 * 이때 {@code type} 은 null 이고 비중 필드는 0 이다.
 *
 * <p>비중은 모두 0~1 (평가금액 가중). {@code top1Share} 는 최대 비중 한 종목의 비중으로
 * 집중/분산 판정에 쓴다.
 */
public record InvestmentProfile(
        boolean classified,
        InvestmentType type,
        BigDecimal domesticShare,
        BigDecimal individualShare,
        BigDecimal top1Share,
        BigDecimal aggressiveShare,
        int holdingCount
) {

    /** 유형을 정할 수 없는 "미분류/신규" 상태. 보유 종목 부족·전액 현금 등. */
    public static InvestmentProfile unclassified(int holdingCount) {
        BigDecimal z = BigDecimal.ZERO;
        return new InvestmentProfile(false, null, z, z, z, z, holdingCount);
    }
}
