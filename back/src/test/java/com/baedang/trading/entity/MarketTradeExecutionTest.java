package com.baedang.trading.entity;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.model.ExecutionRateEvidence;
import com.baedang.trading.model.MarketOrderAmount;
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
    private static final MarketOrderAmount AMOUNT = new MarketOrderAmount(new BigDecimal("10.00"), RATE,
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
        return Stream.of(null,
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

    @Test
    void 정상_주문과의_연결을_검증한다() {
        var evidence = new ExecutionRateEvidence(RATE, AT, AT, AT.plusSeconds(30));
        TradeOrder order = order();
        TradeExecution execution = TradeExecution.market(order, AMOUNT, evidence, AT);

        execution.validateOrder(order);

        assertThatThrownBy(() -> execution.validateOrder(null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("체결과 주문이 일치하지 않습니다");

        TradeOrder anotherOrder = order();
        ReflectionTestUtils.setField(anotherOrder, "orderId", 999L);
        assertThatThrownBy(() -> execution.validateOrder(anotherOrder))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("체결과 주문이 일치하지 않습니다");

        TradeOrder orderWithoutId = order();
        ReflectionTestUtils.setField(orderWithoutId, "orderId", null);
        assertThatThrownBy(() -> execution.validateOrder(orderWithoutId))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("체결과 주문이 일치하지 않습니다");
    }

    @Test
    void 매도_주문의_체결과_연결_검증을_수행한다() {
        OffsetDateTime storedAt = AT.truncatedTo(ChronoUnit.MICROS);
        MarketOrderAmount sellAmount = new MarketOrderAmount(new BigDecimal("10.00"), RATE,
                new BigDecimal("10.00"), new BigDecimal("13836.00000000"),
                new BigDecimal("13836"), BigDecimal.ONE, new BigDecimal("27"), new BigDecimal("13808"), BigDecimal.ZERO);
        TradeOrder sellOrder = TradeOrder.filledMarketOrder(1L, 2L, UUID.randomUUID(), OrderSide.SELL,
                BigDecimal.ONE, sellAmount.executedPrice(), storedAt, RATE, sellAmount.grossAmount(),
                sellAmount.fee(), sellAmount.tax(), sellAmount.netAmount(), storedAt);
        ReflectionTestUtils.setField(sellOrder, "orderId", 4L);

        var evidence = new ExecutionRateEvidence(RATE, AT, AT, AT.plusSeconds(30));
        TradeExecution execution = TradeExecution.market(sellOrder, sellAmount, evidence, AT);

        execution.validateOrder(sellOrder);

        ReflectionTestUtils.setField(sellOrder, "side", null);
        assertThatThrownBy(() -> execution.validateOrder(sellOrder))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("체결 방향과 정산 금액이 일치하지 않습니다");
    }

    @Test
    void 매수_체결에_세금이나_SEC수수료가_있거나_정산금액이_불일치하면_예외를_던진다() {
        var evidence = new ExecutionRateEvidence(RATE, AT, AT, AT.plusSeconds(30));
        TradeOrder order = order();
        TradeExecution execution = TradeExecution.market(order, AMOUNT, evidence, AT);

        ReflectionTestUtils.setField(execution, "taxKrw", BigDecimal.ONE);
        assertThatThrownBy(() -> execution.validateOrder(order))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("체결 방향과 정산 금액이 일치하지 않습니다");

        ReflectionTestUtils.setField(execution, "taxKrw", BigDecimal.ZERO);
        ReflectionTestUtils.setField(execution, "secFeeUsd", new BigDecimal("0.01"));
        assertThatThrownBy(() -> execution.validateOrder(order))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("체결 방향과 정산 금액이 일치하지 않습니다");

        ReflectionTestUtils.setField(execution, "secFeeUsd", BigDecimal.ZERO);
        ReflectionTestUtils.setField(execution, "netAmountKrw", new BigDecimal("99999"));
        assertThatThrownBy(() -> execution.validateOrder(order))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("체결 방향과 정산 금액이 일치하지 않습니다");
    }

    @Test
    void 시장별_USD_거래대금을_계산한다() {
        var evidence = new ExecutionRateEvidence(RATE, AT, AT, AT.plusSeconds(30));
        TradeExecution execution = TradeExecution.market(order(), AMOUNT, evidence, AT);

        assertThatThrownBy(() -> execution.grossAmountUsd(null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("종목 시장이 필요합니다");

        assertThat(execution.grossAmountUsd(MarketCountry.KR))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(execution.grossAmountUsd(MarketCountry.US))
                .isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void 시장가_주문조건이_부합하지_않으면_예외를_던진다() {
        var evidence = new ExecutionRateEvidence(RATE, AT, AT, AT.plusSeconds(30));

        assertThatThrownBy(() -> TradeExecution.market(null, AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");

        assertThatThrownBy(() -> TradeExecution.market(order(), null, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");

        TradeOrder nonMarketOrder = order();
        ReflectionTestUtils.setField(nonMarketOrder, "orderType", OrderType.LIMIT);
        assertThatThrownBy(() -> TradeExecution.market(nonMarketOrder, AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");

        TradeOrder nonFilledOrder = order();
        ReflectionTestUtils.setField(nonFilledOrder, "status", OrderStatus.PENDING);
        assertThatThrownBy(() -> TradeExecution.market(nonFilledOrder, AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");

        TradeOrder priceMismatch = order();
        ReflectionTestUtils.setField(priceMismatch, "executedPrice", new BigDecimal("999"));
        assertThatThrownBy(() -> TradeExecution.market(priceMismatch, AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");

        TradeOrder rateMismatch = order();
        ReflectionTestUtils.setField(rateMismatch, "exchangeRate", new BigDecimal("999"));
        assertThatThrownBy(() -> TradeExecution.market(rateMismatch, AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");

        TradeOrder grossMismatch = order();
        ReflectionTestUtils.setField(grossMismatch, "grossAmount", new BigDecimal("999"));
        assertThatThrownBy(() -> TradeExecution.market(grossMismatch, AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");

        TradeOrder feeMismatch = order();
        ReflectionTestUtils.setField(feeMismatch, "fee", new BigDecimal("999"));
        assertThatThrownBy(() -> TradeExecution.market(feeMismatch, AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");

        TradeOrder taxMismatch = order();
        ReflectionTestUtils.setField(taxMismatch, "tax", new BigDecimal("999"));
        assertThatThrownBy(() -> TradeExecution.market(taxMismatch, AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");

        TradeOrder netMismatch = order();
        ReflectionTestUtils.setField(netMismatch, "netAmount", new BigDecimal("999"));
        assertThatThrownBy(() -> TradeExecution.market(netMismatch, AMOUNT, evidence, AT))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("즉시 체결된 시장가 주문이 필요합니다");
    }

    @Test
    void 체결_필드_접근자를_검증한다() {
        var evidence = new ExecutionRateEvidence(RATE, AT, AT, AT.plusSeconds(30));
        TradeOrder order = order();
        TradeExecution execution = TradeExecution.market(order, AMOUNT, evidence, AT);
        ReflectionTestUtils.setField(execution, "executionId", 100L);

        assertThat(execution.getExecutionId()).isEqualTo(100L);
        assertThat(execution.getOrderId()).isEqualTo(3L);
        assertThat(execution.getExecutionKey()).isEqualTo(order.getClientOrderId());
        assertThat(execution.getSequenceNo()).isEqualTo(1);
        assertThat(execution.getQuantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(execution.getPrice()).isEqualByComparingTo(AMOUNT.executedPrice());
        assertThat(execution.getExchangeRate()).isEqualByComparingTo(RATE);
        assertThat(execution.getSecFeeUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(execution.getGrossAmountKrw()).isEqualByComparingTo(AMOUNT.grossAmount());
        assertThat(execution.getFeeKrw()).isEqualByComparingTo(AMOUNT.fee());
        assertThat(execution.getTaxKrw()).isEqualByComparingTo(AMOUNT.tax());
        assertThat(execution.getNetAmountKrw()).isEqualByComparingTo(AMOUNT.netAmount());
        assertThat(execution.getQuoteAt()).isEqualTo(order.getQuoteAt());
        assertThat(execution.getExecutedAt()).isEqualTo(order.getOrderedAt());
        assertThat(execution.getBookLevelId()).isNull();
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
