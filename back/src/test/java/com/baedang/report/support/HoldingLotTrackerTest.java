package com.baedang.report.support;

import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.HoldingReplayEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingLotTrackerTest {

    private static HoldingReplayEvent exec(OrderSide side, long qty, String executedAt) {
        return new HoldingReplayEvent(1L, side, BigDecimal.valueOf(qty), OffsetDateTime.parse(executedAt));
    }

    @Test
    void 체결_이력이_없으면_null() {
        assertThat(HoldingLotTracker.currentLotStart(List.of())).isNull();
    }

    @Test
    void 단일_매수는_그_체결시각이_lot_시작() {
        OffsetDateTime start = HoldingLotTracker.currentLotStart(List.of(
                exec(OrderSide.BUY, 10, "2026-08-01T00:00:00Z")));
        assertThat(start).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void 추가매수와_부분매도는_lot을_리셋하지_않는다() {
        OffsetDateTime start = HoldingLotTracker.currentLotStart(List.of(
                exec(OrderSide.BUY, 10, "2026-08-01T00:00:00Z"),
                exec(OrderSide.BUY, 5, "2026-08-10T00:00:00Z"),
                exec(OrderSide.SELL, 8, "2026-08-20T00:00:00Z")));
        // 잔여 7주 여전히 최초 매수의 lot
        assertThat(start).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void 전량매도_후_재매수는_새_lot() {
        OffsetDateTime start = HoldingLotTracker.currentLotStart(List.of(
                exec(OrderSide.BUY, 10, "2026-08-01T00:00:00Z"),
                exec(OrderSide.SELL, 10, "2026-08-15T00:00:00Z"),  // 전량 매도 → lot 종료
                exec(OrderSide.BUY, 4, "2026-09-05T00:00:00Z")));  // 재매수 → 새 lot
        assertThat(start).isEqualTo(OffsetDateTime.parse("2026-09-05T00:00:00Z"));
    }

    @Test
    void 전량매도로_보유가_없으면_null() {
        OffsetDateTime start = HoldingLotTracker.currentLotStart(List.of(
                exec(OrderSide.BUY, 10, "2026-08-01T00:00:00Z"),
                exec(OrderSide.SELL, 10, "2026-08-15T00:00:00Z")));
        assertThat(start).isNull();
    }

    @Test
    void 전량매도_뒤_체결된_지정가는_체결시각이_새_lot_시작() {
        // 리뷰 시나리오: 8/1 매수 10주 → 9/8 전량 매도 → 9/9 지정가 5주 체결.
        // 주문 접수 순서가 아니라 실제 체결 시각순으로 재생해야 현재 lot 이 9/9 로 잡힌다.
        OffsetDateTime start = HoldingLotTracker.currentLotStart(List.of(
                exec(OrderSide.BUY, 10, "2026-08-01T00:00:00Z"),
                exec(OrderSide.SELL, 10, "2026-09-08T00:00:00Z"),  // 기존 물량 전량 매도 → lot 종료
                exec(OrderSide.BUY, 5, "2026-09-09T00:00:00Z")));  // 지정가 체결 → 새 lot
        assertThat(start).isEqualTo(OffsetDateTime.parse("2026-09-09T00:00:00Z"));
    }
}
