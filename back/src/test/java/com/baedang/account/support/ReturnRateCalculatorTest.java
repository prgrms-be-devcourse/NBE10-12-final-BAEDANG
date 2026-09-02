package com.baedang.account.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnRateCalculatorTest {

    @ParameterizedTest
    @CsvSource({
            "1, 8, 0.1250",
            "-1, 8, -0.1250",
            "0, 8, 0.0000",
            "1, 3, 0.3333",
            "1.2344, 100, 0.0123",
            "1.2345, 100, 0.0123",
            "1.235, 100, 0.0124",
            "-1.235, 100, -0.0124",
            "200, 100, 2.0000"
    })
    void 손익률을_백분율_변환없이_네자리_HALF_UP으로_계산한다(String pnl, String cost, String expected) {
        assertThat(ReturnRateCalculator.calculate(new BigDecimal(pnl), new BigDecimal(cost)))
                .isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000000", "-1"})
    void 취득원가가_양수가_아니면_손익률이_없다(String cost) {
        assertThat(ReturnRateCalculator.calculate(BigDecimal.TEN, new BigDecimal(cost))).isNull();
    }

    @Test
    void 입력_금액의_소수점과_스케일은_바꾸지_않는다() {
        BigDecimal pnl = new BigDecimal("12.345678");
        BigDecimal cost = new BigDecimal("100.123456");
        ReturnRateCalculator.calculate(pnl, cost);
        assertThat(pnl).isEqualTo(new BigDecimal("12.345678"));
        assertThat(cost).isEqualTo(new BigDecimal("100.123456"));
    }
}
