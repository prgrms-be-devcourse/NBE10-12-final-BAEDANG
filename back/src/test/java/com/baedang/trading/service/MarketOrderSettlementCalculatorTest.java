package com.baedang.trading.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.OrderAmount;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketOrderSettlementCalculatorTest {

    private MarketOrderSettlementCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new MarketOrderSettlementCalculator(
                new BigDecimal("0.0001"),
                new BigDecimal("0.002"),
                new BigDecimal("0.0000206"),
                new BigDecimal("0.01")
        );
    }

    @Test
    void 국내_매수는_주문금액과_수수료를_원단위로_반올림한다() {
        OrderAmount result = calculator.calculate(
                MarketCountry.KR,
                OrderSide.BUY,
                new BigDecimal("241500"),
                new BigDecimal("10"),
                BigDecimal.ONE
        );

        assertThat(result.grossAmount()).isEqualByComparingTo("2415000");
        assertThat(result.grossAmountUsd()).isEqualByComparingTo("0");
        assertThat(result.unroundedGrossAmountKrw()).isEqualByComparingTo("2415000");
        assertThat(result.fee()).isEqualByComparingTo("242");
        assertThat(result.tax()).isEqualByComparingTo("0");
        assertThat(result.netAmount()).isEqualByComparingTo("2415242");
    }

    @Test
    void 국내_매도는_수수료와_증권거래세를_차감한다() {
        OrderAmount result = calculator.calculate(
                MarketCountry.KR,
                OrderSide.SELL,
                new BigDecimal("241500"),
                new BigDecimal("10"),
                BigDecimal.ONE
        );

        assertThat(result.grossAmount()).isEqualByComparingTo("2415000");
        assertThat(result.fee()).isEqualByComparingTo("242");
        assertThat(result.tax()).isEqualByComparingTo("4830");
        assertThat(result.netAmount()).isEqualByComparingTo("2409928");
    }

    @Test
    void 미국_매수는_주당가격을_센트로_반올림한_뒤_수량과_환율을_적용한다() {
        OrderAmount result = calculator.calculate(
                MarketCountry.US,
                OrderSide.BUY,
                new BigDecimal("88.335"),
                new BigDecimal("10"),
                new BigDecimal("1383.60")
        );

        assertThat(result.executedPrice()).isEqualByComparingTo("88.34");
        assertThat(result.grossAmountUsd()).isEqualByComparingTo("883.40");
        assertThat(result.unroundedGrossAmountKrw()).isEqualByComparingTo("1222272.24");
        assertThat(result.grossAmount()).isEqualByComparingTo("1222272");
        assertThat(result.fee()).isEqualByComparingTo("122");
        assertThat(result.tax()).isEqualByComparingTo("0");
        assertThat(result.netAmount()).isEqualByComparingTo("1222394");
    }

    @Test
    void 미국_매도는_SEC_Fee_최소_1센트를_원화로_환산한다() {
        OrderAmount result = calculator.calculate(
                MarketCountry.US,
                OrderSide.SELL,
                new BigDecimal("88.33"),
                BigDecimal.ONE,
                new BigDecimal("1383.60")
        );

        assertThat(result.grossAmount()).isEqualByComparingTo("122213");
        assertThat(result.fee()).isEqualByComparingTo("12");
        assertThat(result.tax()).isEqualByComparingTo("14");
        assertThat(result.netAmount()).isEqualByComparingTo("122187");
        assertThat(result.netAmount())
                .isEqualByComparingTo(result.grossAmount().subtract(result.fee()).subtract(result.tax()));
    }

    @Test
    void 반올림_전_원시단가의_소수점은_제한하지_않고_정산단가를_검증한다() {
        OrderAmount result = calculator.calculate(MarketCountry.US, OrderSide.BUY,
                new BigDecimal("88.335123456"), BigDecimal.ONE, new BigDecimal("1383.600001"));
        assertThat(result.executedPrice()).isEqualByComparingTo("88.34");
        assertThat(result.exchangeRate()).isEqualTo(new BigDecimal("1383.600001"));
    }

    @Test
    void 단가_저장_상한은_센트_반올림_후_확인한다() {
        OrderAmount result = calculator.calculate(MarketCountry.US, OrderSide.BUY,
                new BigDecimal("999999999999999.994"), BigDecimal.ONE, new BigDecimal("0.000001"));
        assertThat(result.executedPrice()).isEqualByComparingTo("999999999999999.99");
        assertThatThrownBy(() -> calculator.calculate(MarketCountry.US, OrderSide.BUY,
                new BigDecimal("999999999999999.995"), BigDecimal.ONE, new BigDecimal("0.000001")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_SETTLEMENT_AMOUNT));
    }

    @ParameterizedTest
    @CsvSource({"500000000000000, 2", "999950000000000, 1", "10.00001, 1"})
    void 거래대금_최종결제액_또는_단가가_저장범위를_넘으면_거절한다(BigDecimal price, BigDecimal quantity) {
        assertThatThrownBy(() -> calculator.calculate(MarketCountry.KR, OrderSide.BUY, price, quantity, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_SETTLEMENT_AMOUNT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000000000000", "1383.6000001", "0", "-1"})
    void 미국_환율은_반올림하지_않고_원본_저장범위를_검증한다(BigDecimal rate) {
        assertThatThrownBy(() -> calculator.calculate(MarketCountry.US, OrderSide.BUY,
                BigDecimal.ONE, BigDecimal.ONE, rate))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000000000000", "1.0000001", "0", "-1"})
    void 수량의_저장범위를_검증한다(BigDecimal quantity) {
        assertThatThrownBy(() -> calculator.calculate(MarketCountry.KR, OrderSide.BUY,
                BigDecimal.ONE, quantity, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY));
    }

    @ParameterizedTest
    @CsvSource({"0.01, 0", "0.001, -14"})
    void 소액_매도의_0이하_정산액은_기존_주문_거절정책에_전달한다(BigDecimal price, BigDecimal expectedNet) {
        OrderAmount result = calculator.calculate(MarketCountry.US, OrderSide.SELL,
                price, BigDecimal.ONE, new BigDecimal("1400"));
        assertThat(result.netAmount()).isEqualByComparingTo(expectedNet);
    }

    @Test
    void 국내_단가의_기존_소수정밀도와_환율없는_정산을_유지한다() {
        OrderAmount result = calculator.calculate(MarketCountry.KR, OrderSide.BUY,
                new BigDecimal("10.1234"), new BigDecimal("10"), null);
        assertThat(result.executedPrice()).isEqualByComparingTo("10.1234");
        assertThat(result.exchangeRate()).isEqualByComparingTo("1");
        assertThat(result.grossAmount()).isEqualByComparingTo("101");
    }

    @Test
    void 미국_SEC_Fee가_최소금액을_넘으면_계산값을_센트로_반올림한다() {
        OrderAmount result = calculator.calculate(
                MarketCountry.US,
                OrderSide.SELL,
                new BigDecimal("1000"),
                BigDecimal.ONE,
                new BigDecimal("1400")
        );

        assertThat(result.grossAmount()).isEqualByComparingTo("1400000");
        assertThat(result.fee()).isEqualByComparingTo("140");
        assertThat(result.tax()).isEqualByComparingTo("28");
        assertThat(result.netAmount()).isEqualByComparingTo("1399832");
    }
}
