package com.baedang.report.support;

import java.math.BigDecimal;

/**
 * 투자 MBTI 4축의 근거 비중(0~1). 스냅샷 한 시점 또는 4주 창의 시점별 평균을 담는다.
 * 필드 순서는 {@link InvestmentProfile} 과 같다(국내·개별주·top1·공격).
 */
public record AxisShares(
        BigDecimal domesticShare,
        BigDecimal individualShare,
        BigDecimal top1Share,
        BigDecimal aggressiveShare
) {
}
