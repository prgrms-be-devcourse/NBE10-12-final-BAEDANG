package com.baedang.trading.model;

import com.baedang.trading.entity.OrderSide;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 원가 구성 재생을 위한 <b>개별 체결</b> 한 건. {@link HoldingReplayEvent} 와 달리 원화 거래대금
 * ({@code grossAmountKrw})을 함께 담아, 시점별 <b>원가(cost) 구성</b>을 복원할 수 있게 한다
 * (투자 MBTI 원가 4주 평균, 설계문서 §6.2).
 *
 * <p>방향은 주문이 소유하므로 {@code trade_order} 를 조인한다. 매수는 원가·수량을 더하고,
 * 매도는 평균원가법으로 비례 차감한다(재생 로직은 소비 측 담당).
 */
public record CostReplayEvent(
        Long stockId,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal grossAmountKrw,
        OffsetDateTime executedAt
) {
}
