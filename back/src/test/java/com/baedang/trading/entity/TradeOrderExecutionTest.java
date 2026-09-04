package com.baedang.trading.entity;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.model.ExecutionAmounts;
import com.baedang.trading.model.ExecutionRateEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class TradeOrderExecutionTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-03T01:00:00Z");
    private static final ExecutionRateEvidence RATE = new ExecutionRateEvidence(
            BigDecimal.ONE, AT, AT, AT.plusMinutes(1));

    @Test
    void 지정가_접수시각이_없으면_입력검증_예외로_거절한다() {
        assertThatThrownBy(() -> TradeOrder.pendingLimitOrder(1L, 2L, UUID.randomUUID(),
                OrderSide.BUY, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                null, AT.plusHours(6)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("지정가 접수 근거가 올바르지 않습니다");
    }

    @ParameterizedTest
    @CsvSource({
            "KR, 1400, 0, 0",
            "KR, 1, 100, 0",
            "KR, 1, 0, 0.01",
            "US, 1400, 0, 0",
            "US, 1400, 99, 0"
    })
    void 시장과_맞지_않는_환율_USD거래대금_SEC비용은_거절한다(
            MarketCountry country, BigDecimal exchangeRate, BigDecimal grossUsd, BigDecimal secFeeUsd) {
        TradeOrder order = order(OrderSide.SELL);
        BigDecimal grossKrw = new BigDecimal("100").multiply(exchangeRate);
        var rate = new ExecutionRateEvidence(exchangeRate, AT, AT, AT.plusMinutes(1));
        var amounts = new ExecutionAmounts(grossUsd, grossKrw, secFeeUsd,
                grossKrw, BigDecimal.ZERO, BigDecimal.ZERO, grossKrw);

        assertThatThrownBy(() -> TradeExecution.limit(order, country, UUID.randomUUID(), 1,
                BigDecimal.ONE, new BigDecimal("100"), rate, amounts, AT, AT.plusSeconds(1), 1L))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("종목 시장과 체결 환율·USD 거래대금·SEC 비용이 일치하지 않습니다");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getFilledQuantity()).isZero();
    }

    @ParameterizedTest
    @EnumSource(MarketCountry.class)
    void 환율이_1이어도_시장별_USD거래대금을_구분하고_원본정밀도를_유지한다(MarketCountry country) {
        TradeOrder order = order(OrderSide.SELL);
        BigDecimal originalRate = new BigDecimal("1.000000");
        BigDecimal grossUsd = country == MarketCountry.KR ? BigDecimal.ZERO : new BigDecimal("100");
        var rate = new ExecutionRateEvidence(originalRate, AT, AT, AT.plusMinutes(1));
        var amounts = new ExecutionAmounts(grossUsd, new BigDecimal("100.000000"), BigDecimal.ZERO,
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100"));

        TradeExecution execution = TradeExecution.limit(order, country, UUID.randomUUID(), 1,
                BigDecimal.ONE, new BigDecimal("100"), rate, amounts, AT, AT.plusSeconds(1), 1L);

        assertThat(execution.getExchangeRate()).isEqualTo(originalRate);
        assertThat(execution.grossAmountUsd(country)).isEqualByComparingTo(grossUsd);
        assertThat(execution.getSecFeeUsd()).isZero();
    }

    @Test
    void 지정가_체결의_시장과_정산금액은_필수다() {
        TradeOrder order = order(OrderSide.BUY);
        assertThatThrownBy(() -> TradeExecution.limit(order, null, UUID.randomUUID(), 1,
                BigDecimal.ONE, new BigDecimal("100"), RATE, amount("100"), AT, AT.plusSeconds(1), 1L))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1,
                BigDecimal.ONE, new BigDecimal("100"), RATE, null, AT, AT.plusSeconds(1), 1L))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 부분체결_후_전량체결은_금액을_누적하고_동결을_종료한다() {
        TradeOrder order = order(OrderSide.BUY);
        TradeExecution first = execution(order, "1", "90");
        assertThat(order.getReservedCash()).isEqualByComparingTo("300");
        assertThat(first.grossAmountUsd(com.baedang.stock.entity.MarketCountry.KR)).isZero();
        assertThat(first.unroundedGrossAmountKrw()).isEqualByComparingTo("90");
        order.applyExecution(first, new BigDecimal("110"));
        assertThat(order.getReservedCash()).isEqualByComparingTo("110");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.activeRemainingQuantity()).isEqualByComparingTo("2");
        assertThat(order.getFilledQuantity()).isEqualByComparingTo("1");
        assertThat(order.getClosedAt()).isNull();
        assertThatThrownBy(() -> order.applyExecution(first, new BigDecimal("110")))
                .isInstanceOf(IllegalArgumentException.class);
        order.applyExecution(execution(order, "2", "180"), BigDecimal.ZERO);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getGrossAmount()).isEqualByComparingTo("270");
        assertThat(order.getExecutionCount()).isEqualTo(2);
        assertThat(order.getReservedCash()).isZero();
        assertThat(order.activeRemainingQuantity()).isZero();
        assertThat(order.getClosedAt()).isEqualTo(AT.plusSeconds(2));
        assertThatThrownBy(() -> order.cancel(AT.plusSeconds(3))).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CANCELED", "EXPIRED"})
    void 부분체결_종료와_반복요청은_체결분을_보존한다(OrderStatus target) {
        TradeOrder order = order(OrderSide.BUY);
        order.applyExecution(execution(order, "1", "90"), new BigDecimal("200"));
        boolean closed = target == OrderStatus.CANCELED ? order.cancel(AT.plusSeconds(3)) : order.expire(AT.plusHours(6));
        assertThat(closed).isTrue();
        assertThat(target == OrderStatus.CANCELED ? order.cancel(AT.plusSeconds(4)) : order.expire(AT.plusHours(7))).isFalse();
        assertThat(order.getStatus()).isEqualTo(target);
        assertThat(order.getFilledQuantity()).isEqualByComparingTo("1");
        assertThat(order.getGrossAmount()).isEqualByComparingTo("90");
        assertThat(order.activeRemainingQuantity()).isZero();
        assertThat(order.getReservedCash()).isZero();
    }


    @Test
    void 지정가_매도는_지정가_이상의_가격에서_원화동결없이_체결한다() {
        TradeOrder order = order(OrderSide.SELL);
        TradeExecution execution = TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1, new BigDecimal("3"),
                new BigDecimal("110"), RATE, amount("330"), AT, AT.plusSeconds(1), 1L);
        ReflectionTestUtils.setField(execution, "executionId", 1L);
        order.applyExecution(execution, BigDecimal.ZERO);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getNetAmount()).isEqualByComparingTo("330");
        assertThat(order.getReservedCash()).isZero();
    }

    @Test
    void 만료경계와_종료동결액을_검증하고_실패시_엔티티를_바꾸지_않는다() {
        TradeOrder order = order(OrderSide.BUY);
        assertThatThrownBy(() -> order.expire(AT.plusHours(5))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.cancel(AT.plusHours(6))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.applyExecution(execution(order, "3", "270"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.applyExecution(execution(order, "1", "90"), new BigDecimal("301")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getFilledQuantity()).isZero();
        assertThat(order.getReservedCash()).isEqualByComparingTo("300");
        assertThatThrownBy(() -> execution(order, "4", "360")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 호가가격과_환율유효구간을_검증한다() {
        TradeOrder order = order(OrderSide.BUY);
        ExecutionAmounts amount = amount("100");
        assertThatThrownBy(() -> TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1, BigDecimal.ONE,
                new BigDecimal("101"), RATE, amount, AT, AT.plusSeconds(1), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1, BigDecimal.ONE,
                new BigDecimal("100"), RATE, amount, AT, AT.plusMinutes(1), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1, BigDecimal.ONE,
                new BigDecimal("100"), ExecutionRateEvidence.rateOnly(BigDecimal.ONE), amount,
                AT, AT.plusSeconds(1), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1, BigDecimal.ONE,
                new BigDecimal("100"), RATE, amount, AT, AT.plusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1, BigDecimal.ONE,
                new BigDecimal("100"), RATE, amount, AT, AT.plusSeconds(1), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정산과_환율_입력값은_검증하되_원본정밀도는_유지한다() {
        BigDecimal raw = new BigDecimal("123.123456789012345678901234567890");
        var amount = new ExecutionAmounts(BigDecimal.ONE, raw, BigDecimal.ZERO,
                new BigDecimal("123"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("123"));
        assertThat(amount.unroundedGrossAmountKrw()).isEqualTo(raw);
        assertThatThrownBy(() -> new ExecutionRateEvidence(BigDecimal.ZERO, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionRateEvidence(BigDecimal.ONE, AT, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionAmounts(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE).validateSide(OrderSide.BUY))
                .isInstanceOf(IllegalArgumentException.class);

        TradeOrder order = order(OrderSide.BUY);
        assertThatThrownBy(() -> TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1, BigDecimal.ONE,
                new BigDecimal("90"), RATE, amount("100"), AT, AT.plusSeconds(1), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        ExecutionAmounts mismatchedUsd = new ExecutionAmounts(new BigDecimal("99"), new BigDecimal("90"),
                BigDecimal.ZERO, new BigDecimal("90"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("90"));
        assertThatThrownBy(() -> TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), 1, BigDecimal.ONE,
                new BigDecimal("90"), RATE, mismatchedUsd, AT, AT.plusSeconds(1), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 다른_주문의_체결은_반영하지_않는다() {
        TradeOrder order = order(OrderSide.BUY);
        TradeOrder other = order(OrderSide.BUY);
        ReflectionTestUtils.setField(other, "orderId", 4L);
        assertThatThrownBy(() -> order.applyExecution(execution(other, "1", "90"), new BigDecimal("200")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getFilledQuantity()).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void 환율이_다른_체결의_누적세금은_SEC차액과_각_적용환율로_복원한다() {
        TradeOrder order = TradeOrder.pendingLimitOrder(1L, 2L, UUID.randomUUID(), OrderSide.SELL,
                new BigDecimal("2"), new BigDecimal("1000"), BigDecimal.ZERO,
                AT, AT.plusHours(6));
        ReflectionTestUtils.setField(order, "orderId", 3L);
        var firstRate = new ExecutionRateEvidence(new BigDecimal("1324.6"), AT, AT, AT.plusMinutes(1));
        var secondRate = new ExecutionRateEvidence(new BigDecimal("1424.6"), AT.plusMinutes(2),
                AT.plusMinutes(2), AT.plusMinutes(3));
        // 누적 정산 결과: USD 1,000씩의 SEC 차액은 각각 0.02, 원화 누적 세금은 26 → 55입니다.
        var firstAmounts = new ExecutionAmounts(new BigDecimal("1000"), new BigDecimal("1324600"),
                new BigDecimal("0.02"), new BigDecimal("1324600"), new BigDecimal("132"),
                new BigDecimal("26"), new BigDecimal("1324442"));
        var secondAmounts = new ExecutionAmounts(new BigDecimal("1000"), new BigDecimal("1424600"),
                new BigDecimal("0.02"), new BigDecimal("1424600"), new BigDecimal("143"),
                new BigDecimal("29"), new BigDecimal("1424428"));
        TradeExecution first = TradeExecution.limit(order, MarketCountry.US, UUID.randomUUID(), 1, BigDecimal.ONE,
                new BigDecimal("1000"), firstRate, firstAmounts, AT, AT.plusSeconds(1), 1L);
        ReflectionTestUtils.setField(first, "executionId", 1L);
        order.applyExecution(first, BigDecimal.ZERO);
        TradeExecution second = TradeExecution.limit(order, MarketCountry.US, UUID.randomUUID(), 2, BigDecimal.ONE,
                new BigDecimal("1000"), secondRate, secondAmounts, AT.plusMinutes(2), AT.plusMinutes(2), 1L);
        ReflectionTestUtils.setField(second, "executionId", 2L);
        order.applyExecution(second, BigDecimal.ZERO);

        BigDecimal rawTaxKrw = first.getSecFeeUsd().multiply(first.getExchangeRate())
                .add(second.getSecFeeUsd().multiply(second.getExchangeRate()));
        assertThat(rawTaxKrw).isEqualByComparingTo("54.984");
        assertThat(rawTaxKrw.setScale(0, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo(order.getTax());
        assertThat(order.getTax()).isEqualByComparingTo("55");
        assertThat(first.getExchangeRate()).isEqualByComparingTo("1324.6");
        assertThat(second.getExchangeRate()).isEqualByComparingTo("1424.6");
        assertThat(order.getNetAmount()).isEqualByComparingTo("2748870");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    private TradeOrder order(OrderSide side) {
        TradeOrder order = TradeOrder.pendingLimitOrder(1L, 2L, UUID.randomUUID(), side, new BigDecimal("3"),
                new BigDecimal("100"), side == OrderSide.BUY ? new BigDecimal("300") : BigDecimal.ZERO,
                AT, AT.plusHours(6));
        ReflectionTestUtils.setField(order, "orderId", 3L);
        return order;
    }

    private TradeExecution execution(TradeOrder order, String quantity, String gross) {
        TradeExecution execution = TradeExecution.limit(order, MarketCountry.KR, UUID.randomUUID(), order.getExecutionCount() + 1,
                new BigDecimal(quantity), new BigDecimal("90"), RATE, amount(gross), AT,
                AT.plusSeconds(order.getExecutionCount() + 1), 1L);
        ReflectionTestUtils.setField(execution, "executionId", (long) execution.getSequenceNo());
        return execution;
    }

    private ExecutionAmounts amount(String gross) {
        BigDecimal value = new BigDecimal(gross);
        return new ExecutionAmounts(BigDecimal.ZERO, value, BigDecimal.ZERO,
                value, BigDecimal.ZERO, BigDecimal.ZERO, value);
    }
}
