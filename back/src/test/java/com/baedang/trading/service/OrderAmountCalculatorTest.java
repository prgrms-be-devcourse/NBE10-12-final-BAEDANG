package com.baedang.trading.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.model.OrderAmount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderAmountCalculatorTest {

    private OrderAmountCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new OrderAmountCalculator(
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
