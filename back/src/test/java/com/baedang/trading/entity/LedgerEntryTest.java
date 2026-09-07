package com.baedang.trading.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LedgerEntryTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-05T01:00:00Z");

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1"})
    void 매수_매도_원장의_환율_누락과_0_음수는_자동보정하지_않고_거절한다(BigDecimal rate) {
        for (OrderSide side : OrderSide.values()) {
            assertThatThrownBy(() -> executionLedger(side, rate))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("원장 환율은 필수이며 양수여야 합니다");
        }
    }

    @ParameterizedTest
    @EnumSource(OrderSide.class)
    void 유효한_환율은_반올림이나_스케일_변경_없이_보존한다(OrderSide side) {
        BigDecimal rate = new BigDecimal("1383.600001");
        LedgerEntry entry = executionLedger(side, rate);
        assertThat(entry.getExchangeRate()).isEqualTo(rate);
        assertThat(entry.getExecutionId()).isEqualTo(3L);
    }

    private LedgerEntry executionLedger(OrderSide side, BigDecimal rate) {
        TradeOrder order = mock(TradeOrder.class);
        when(order.getAccountId()).thenReturn(1L);
        when(order.getOrderId()).thenReturn(2L);
        when(order.getSide()).thenReturn(side);
        // 원장 자체의 환율 검증을 확인하므로 누락/잘못된 체결 환율도 주입합니다.
        TradeExecution execution = mock(TradeExecution.class);
        when(execution.getExecutionId()).thenReturn(3L);
        when(execution.getNetAmountKrw()).thenReturn(BigDecimal.TEN);
        when(execution.getExchangeRate()).thenReturn(rate);
        when(execution.getExecutedAt()).thenReturn(AT);
        return LedgerEntry.execution(order, execution, BigDecimal.TEN, "테스트");
    }
}
