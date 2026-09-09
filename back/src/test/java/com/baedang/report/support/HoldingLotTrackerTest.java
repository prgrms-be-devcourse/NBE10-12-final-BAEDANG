package com.baedang.report.support;

import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.TradeOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class HoldingLotTrackerTest {

    private static TradeOrder fill(OrderSide side, long qty, String orderedAt) {
        TradeOrder o = mock(TradeOrder.class);
        lenient().when(o.getSide()).thenReturn(side);
        lenient().when(o.getFilledQuantity()).thenReturn(BigDecimal.valueOf(qty));
        lenient().when(o.getOrderedAt()).thenReturn(OffsetDateTime.parse(orderedAt));
        return o;
    }

    @Test
    void 체결_이력이_없으면_null() {
        assertThat(HoldingLotTracker.currentLotStart(List.of())).isNull();
    }

    @Test
    void 단일_매수는_그_시각이_lot_시작() {
        OffsetDateTime start = HoldingLotTracker.currentLotStart(List.of(
                fill(OrderSide.BUY, 10, "2026-08-01T00:00:00Z")));
        assertThat(start).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void 추가매수와_부분매도는_lot을_리셋하지_않는다() {
        OffsetDateTime start = HoldingLotTracker.currentLotStart(List.of(
                fill(OrderSide.BUY, 10, "2026-08-01T00:00:00Z"),
                fill(OrderSide.BUY, 5, "2026-08-10T00:00:00Z"),
                fill(OrderSide.SELL, 8, "2026-08-20T00:00:00Z")));
        // 잔여 7주 여전히 최초 매수의 lot
        assertThat(start).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void 전량매도_후_재매수는_새_lot() {
        OffsetDateTime start = HoldingLotTracker.currentLotStart(List.of(
                fill(OrderSide.BUY, 10, "2026-08-01T00:00:00Z"),
                fill(OrderSide.SELL, 10, "2026-08-15T00:00:00Z"),  // 전량 매도 → lot 종료
                fill(OrderSide.BUY, 4, "2026-09-05T00:00:00Z")));  // 재매수 → 새 lot
        assertThat(start).isEqualTo(OffsetDateTime.parse("2026-09-05T00:00:00Z"));
    }

    @Test
    void 전량매도로_보유가_없으면_null() {
        OffsetDateTime start = HoldingLotTracker.currentLotStart(List.of(
                fill(OrderSide.BUY, 10, "2026-08-01T00:00:00Z"),
                fill(OrderSide.SELL, 10, "2026-08-15T00:00:00Z")));
        assertThat(start).isNull();
    }
}
