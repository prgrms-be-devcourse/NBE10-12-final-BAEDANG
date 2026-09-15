package com.baedang.trading.dto;

import com.baedang.trading.model.LimitExecutionBook;
import com.baedang.trading.model.LimitExecutionPlan;
import com.baedang.trading.model.ExecutionAmounts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static com.baedang.global.formatter.FinancialDecimalFormatter.krw;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;

/** 평균가는 표시 전용입니다. 예상 정산액은 각 호가의 누적 차액 합계이며 평균가로 재계산하지 않습니다. */
public record LimitExecutionPreviewResponse(Status status, String reason, Long bookVersion, Long revision,
        Instant quoteAt, Instant generatedAt, Instant evaluatedAt,
        String expectedFilledQuantity, String remainingQuantity, String avgExecutionPrice,
        String grossAmountKrw, String feeKrw, String taxKrw, String netAmountKrw,
        String remainingReservedCash, String releasedCash) {
    public enum Status { AVAILABLE, UNAVAILABLE, NOT_APPLICABLE }

    public static LimitExecutionPreviewResponse unavailable(Status status, String reason, Instant now) {
        return new LimitExecutionPreviewResponse(status, reason, null, null, null, null, now,
                null, null, null, null, null, null, null, null, null);
    }

    public static LimitExecutionPreviewResponse from(LimitExecutionBook book, LimitExecutionPlan plan,
            int priceScale, Instant now) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal nativeGross = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO, fee = BigDecimal.ZERO, tax = BigDecimal.ZERO, net = BigDecimal.ZERO;
        for (LimitExecutionPlan.Fill fill : plan.fills()) {
            quantity = quantity.add(fill.quantity());
            nativeGross = nativeGross.add(fill.price().multiply(fill.quantity()));
            ExecutionAmounts amount = fill.amounts();
            gross = gross.add(amount.grossAmountKrw());
            fee = fee.add(amount.feeKrw());
            tax = tax.add(amount.taxKrw());
            net = net.add(amount.netAmountKrw());
        }
        String average = quantity.signum() == 0 ? null : nativeGross.divide(quantity, priceScale, RoundingMode.HALF_UP).toPlainString();
        return new LimitExecutionPreviewResponse(Status.AVAILABLE, plan.reason().name(), book.version(), book.revision(), book.quoteAt(), book.generatedAt(), now,
                plain(quantity), plain(plan.remainingQuantity()), average, krw(gross), krw(fee), krw(tax), krw(net),
                krw(plan.remainingReservedCash()), krw(plan.releasedCash()));
    }
}
