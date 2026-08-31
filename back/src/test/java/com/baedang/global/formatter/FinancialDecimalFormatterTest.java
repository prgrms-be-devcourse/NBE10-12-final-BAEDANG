package com.baedang.global.formatter;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FinancialDecimalFormatterTest {

    @Test
    void plain은_null과_0을_안전하게_표현한다() {
        assertThat(FinancialDecimalFormatter.plain(null)).isNull();
        assertThat(FinancialDecimalFormatter.plain(new BigDecimal("0.000"))).isEqualTo("0");
    }

    @Test
    void plain은_지수표기와_불필요한_후행0을_제거한다() {
        assertThat(FinancialDecimalFormatter.plain(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(FinancialDecimalFormatter.plain(new BigDecimal("12.3400"))).isEqualTo("12.34");
    }

    @Test
    void preserveScale은_환율의_원본_소수자릿수를_유지한다() {
        assertThat(FinancialDecimalFormatter.preserveScale(new BigDecimal("1383.600000")))
                .isEqualTo("1383.600000");
        assertThat(FinancialDecimalFormatter.preserveScale(new BigDecimal("1E+3")))
                .isEqualTo("1000");
        assertThat(FinancialDecimalFormatter.preserveScale(null)).isNull();
    }

    @Test
    void usd는_HALF_UP으로_반올림하고_센트_두자리를_고정한다() {
        assertThat(FinancialDecimalFormatter.usd(new BigDecimal("88.335"))).isEqualTo("88.34");
        assertThat(FinancialDecimalFormatter.usd(new BigDecimal("88.3"))).isEqualTo("88.30");
        assertThat(FinancialDecimalFormatter.usd(new BigDecimal("-1.005"))).isEqualTo("-1.01");
        assertThat(FinancialDecimalFormatter.usd(BigDecimal.ZERO)).isEqualTo("0.00");
        assertThat(FinancialDecimalFormatter.usd(null)).isNull();
    }

    @Test
    void krw는_HALF_UP으로_반올림하고_원단위로_표현한다() {
        assertThat(FinancialDecimalFormatter.krw(new BigDecimal("12.5"))).isEqualTo("13");
        assertThat(FinancialDecimalFormatter.krw(new BigDecimal("12.49"))).isEqualTo("12");
        assertThat(FinancialDecimalFormatter.krw(new BigDecimal("-12.5"))).isEqualTo("-13");
        assertThat(FinancialDecimalFormatter.krw(BigDecimal.ZERO)).isEqualTo("0");
        assertThat(FinancialDecimalFormatter.krw(null)).isNull();
    }

    @Test
    void currency는_KRW와_USD_표시정책을_선택한다() {
        assertThat(FinancialDecimalFormatter.currency(new BigDecimal("10.5"), "krw"))
                .isEqualTo("11");
        assertThat(FinancialDecimalFormatter.currency(new BigDecimal("10.5"), " usd "))
                .isEqualTo("10.50");
        assertThat(FinancialDecimalFormatter.currency(null, null)).isNull();
    }

    @Test
    void currency는_지원하지_않거나_빈_통화를_거절한다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FinancialDecimalFormatter.currency(BigDecimal.ONE, "EUR"))
                .withMessage("Unsupported currency: EUR");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FinancialDecimalFormatter.currency(BigDecimal.ONE, " "))
                .withMessage("currency must not be blank");
    }
}
