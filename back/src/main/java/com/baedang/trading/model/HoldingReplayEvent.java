package com.baedang.trading.model;

import com.baedang.trading.entity.OrderSide;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 보유 lot 재생을 위한 <b>개별 체결</b> 한 건. {@code trade_execution} 을 {@code trade_order}
 * 에 조인해 방향(주문 소유)과 체결 수량·체결 시각을 함께 담는다.
 *
 * <p>주문 요약({@code filled_quantity}·{@code ordered_at})이 아니라 체결 단위라, 부분 체결
 * 사이에 매도가 끼거나 지정가 접수와 체결 시각이 벌어지는 경우에도 순서를 정확히 복원한다.
 * DB 컬럼이 아니라 조회 프로젝션이다.
 */
public record HoldingReplayEvent(
        Long stockId,
        OrderSide side,
        BigDecimal quantity,
        OffsetDateTime executedAt
) {
}
