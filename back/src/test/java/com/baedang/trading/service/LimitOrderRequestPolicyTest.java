package com.baedang.trading.service;

import com.baedang.global.error.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitOrderRequestPolicyTest {

    @ParameterizedTest
    @CsvSource({"KRW,1.1", "USD,1.001", "USD,0", "KRW,-1", "USD,1e3", "KRW,1000000000000000"})
    void 초과정밀도는_반올림하지_않고_거절한다(String currency, String price) {
        assertThatThrownBy(() -> LimitOrderRequestPolicy.price(price, currency))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @CsvSource({"KRW,1.00,1", "USD,1.2300,1.23"})
    void 후행영은_허용한다(String currency, String price, String expected) {
        assertThat(LimitOrderRequestPolicy.price(price, currency))
                .isEqualByComparingTo(expected);
    }
}
