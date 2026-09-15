package com.baedang.account.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 계좌·보유 종목의 손익률 계산. 금액 정산이나 응답 문자열 포맷팅은 담당하지 않습니다. */
public final class ReturnRateCalculator {

    private ReturnRateCalculator() {
    }

    /**
     * 손익 / 취득원가를 소수점 4자리 HALF_UP으로 계산합니다. 입력은 계산 완료된 non-null 금액입니다.
     * 취득원가가 0 이하이면 비율을 정의할 수 없어 null을 반환하며, 백분율로 변환하지 않습니다.
     * 환율·주가 등락률의 별도 정밀도 정책에는 이 계산기를 사용하지 않습니다.
     */
    public static BigDecimal calculate(BigDecimal pnl, BigDecimal cost) {
        return cost.signum() > 0 ? pnl.divide(cost, 4, RoundingMode.HALF_UP) : null;
    }
}
