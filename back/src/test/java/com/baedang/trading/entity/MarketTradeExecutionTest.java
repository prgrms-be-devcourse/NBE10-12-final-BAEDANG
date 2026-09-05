package com.baedang.trading.entity;

import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.OrderAmount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketTradeExecutionTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-05T01:00:00.123456789Z");
    private static final BigDecimal RATE = new BigDecimal("1383.600000");
    private static final OrderAmount AMOUNT = new OrderAmount(new BigDecimal("10.00"), RATE,
            new BigDecimal("10.00"), new BigDecimal("13836.00000000"),
            new BigDecimal("13836"), BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("13837"), BigDecimal.ZERO);

    @Test
    void 원본_검증시각으로_환율을_확인하고_저장시각_절삭은_유효기간에_영향을_주지_않는다() {
        var evidence = new ExecutionRateEvidence(RATE, AT, AT, AT.plusSeconds(30));
        TradeExecution execution = TradeExecution.market(order(), AMOUNT, evidence, AT);

        assertThat(execution.getExchangeRate()).isEqualTo(RATE);
        assertThat(execution.getExecutedAt()).isEqualTo(AT.truncatedTo(ChronoUnit.MICROS));
        assertThat(execution.unroundedGrossAmountKrw()).isEqualByComparingTo(AMOUNT.unroundedGrossAmountKrw());
    }

    @ParameterizedTest
    @MethodSource("invalidEvidence")
    void 누락_만료_미래_또는_금액과_다른_환율_근거를_거절한다(ExecutionRateEvidence evidence) {
        assertThatThrownBy(() -> TradeExecution.market(order(), AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("시장가 체결 환율과 유효 시각 근거가 일치하지 않습니다");
    }

    static Stream<ExecutionRateEvidence> invalidEvidence() {
        return Stream.of(null, new ExecutionRateEvidence(RATE, null, null, null),
                new ExecutionRateEvidence(RATE, AT.minusSeconds(1), AT.minusSeconds(1), AT),
                new ExecutionRateEvidence(RATE, AT.minusSeconds(60), AT.minusMinutes(5), AT.plusMinutes(5)),
                new ExecutionRateEvidence(RATE, AT.plusNanos(1), AT, AT.plusSeconds(30)),
                new ExecutionRateEvidence(RATE, AT, AT.plusNanos(1), AT.plusSeconds(30)),
                new ExecutionRateEvidence(new BigDecimal("1400"), AT, AT, AT.plusSeconds(30)));
    }

    @Test
    void 체결시각과_다른_검증시각은_환율이_유효해도_거절한다() {
        var evidence = new ExecutionRateEvidence(RATE, AT, AT, AT.plusSeconds(30));
        assertThatThrownBy(() -> TradeExecution.market(order(), AMOUNT, evidence, AT.plusSeconds(1)))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradeExecution.market(order(), AMOUNT, evidence, null))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private TradeOrder order() {
        OffsetDateTime storedAt = AT.truncatedTo(ChronoUnit.MICROS);
        TradeOrder order = TradeOrder.filledMarketOrder(1L, 2L, UUID.randomUUID(), OrderSide.BUY,
                BigDecimal.ONE, AMOUNT.executedPrice(), storedAt, RATE, AMOUNT.grossAmount(),
                AMOUNT.fee(), AMOUNT.tax(), AMOUNT.netAmount(), storedAt);
        ReflectionTestUtils.setField(order, "orderId", 3L);
        return order;
    }
}
