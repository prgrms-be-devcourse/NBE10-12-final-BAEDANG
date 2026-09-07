package com.baedang.trading.model;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import java.math.BigDecimal;

/** 보류 시 금액을 보정하지 않고 원래 누적 상태를 반환합니다. amounts는 실행 가능한 경우에만 존재합니다. */
public record LimitOrderSettlementResult(BigDecimal netAmountKrw, ExecutionAmounts amounts,
                                         CumulativeSettlementState nextState) {
    public LimitOrderSettlementResult {
        if (netAmountKrw == null || nextState == null
                || (netAmountKrw.signum() > 0) != (amounts != null)
                || (amounts != null && amounts.netAmountKrw().compareTo(netAmountKrw) != 0)) {
            throw new IllegalArgumentException("지정가 정산 결과가 올바르지 않습니다");
        }
    }

    public boolean isExecutable() {
        return amounts != null;
    }

    /** 보류 결과로 체결 엔티티를 만들지 못하도록 저장 경계에서 사용합니다. */
    public ExecutionAmounts requireExecutionAmounts() {
        if (!isExecutable()) throw new BusinessException(ErrorCode.INVALID_SETTLEMENT_AMOUNT);
        return amounts;
    }
}
