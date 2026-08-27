package com.baedang.trading.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldingTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 26, 0, 0, 0, 0, ZoneOffset.UTC);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1"})
    void 추가_매수_가격은_0보다_커야_한다(String value) {
        Holding holding = holding();
        BigDecimal price = value == null ? null : new BigDecimal(value);

        assertThatThrownBy(() -> holding.addBuy(
                BigDecimal.ONE, price, new BigDecimal("1400"), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("매수 가격은 0보다 커야 합니다");
        assertUnchanged(holding);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1"})
    void 추가_매수_환율은_0보다_커야_한다(String value) {
        Holding holding = holding();
        BigDecimal rate = value == null ? null : new BigDecimal(value);

        assertThatThrownBy(() -> holding.addBuy(
                BigDecimal.ONE, new BigDecimal("100"), rate, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("매수 환율은 0보다 커야 합니다");
        assertUnchanged(holding);
    }

    private Holding holding() {
        return Holding.firstBuy(
                1L, 1L, new BigDecimal("2"), new BigDecimal("100"),
                new BigDecimal("1380"), NOW);
    }

    private void assertUnchanged(Holding holding) {
        assertThat(holding.getQuantity()).isEqualByComparingTo("2");
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("100");
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo("1380");
        assertThat(holding.getUpdatedAt()).isEqualTo(NOW);
    }
}
