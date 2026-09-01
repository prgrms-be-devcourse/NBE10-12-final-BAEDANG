package com.baedang.trading.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldingTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 26, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void 국내_최초_매수는_원화매수금액으로_평단가를_계산한다() {
        Holding holding = Holding.firstBuy(
                1L, 1L, new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("200"), NOW);

        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("100.0000");
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo("1");
        assertThat(holding.getUsdPurchaseAmount()).isEqualByComparingTo("0");
        assertThat(holding.getKrwPurchaseAmount()).isEqualByComparingTo("200");
    }

    @Test
    void 미국_최초_매수는_USD와_원화매수금액으로_평단가와_평균환율을_계산한다() {
        Holding holding = Holding.firstBuy(
                1L, 1L, new BigDecimal("2"), new BigDecimal("200"),
                new BigDecimal("276000"), NOW);

        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("100.0000");
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo("1380.000000");
    }

    @Test
    void 열번_분할매수해도_반올림된_평균값이_아니라_매수금액_합계로_계산한다() {
        BigDecimal totalUsd = new BigDecimal("10.01");
        BigDecimal totalKrw = new BigDecimal("13013");
        Holding holding = Holding.firstBuy(
                1L, 1L, BigDecimal.ONE, totalUsd, totalKrw, NOW);

        for (int i = 2; i <= 10; i++) {
            BigDecimal price = new BigDecimal("10").add(new BigDecimal(i).movePointLeft(2));
            BigDecimal rate = new BigDecimal(1299 + i);
            BigDecimal unroundedGrossKrw = price.multiply(rate);

            holding.addBuy(
                    BigDecimal.ONE, price, unroundedGrossKrw, NOW.plusSeconds(i));
            totalUsd = totalUsd.add(price);
            totalKrw = totalKrw.add(unroundedGrossKrw);
        }

        assertThat(holding.getQuantity()).isEqualByComparingTo("10");
        assertThat(holding.getUsdPurchaseAmount()).isEqualByComparingTo(totalUsd);
        assertThat(holding.getKrwPurchaseAmount()).isEqualByComparingTo(totalKrw);
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo(
                totalUsd.divide(new BigDecimal("10"), 4, RoundingMode.HALF_UP));
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo(
                totalKrw.divide(totalUsd, 6, RoundingMode.HALF_UP));
    }

    @Test
    void 부분_매도는_잔여수량_비율로_매수금액을_안분하고_평균값은_유지한다() {
        Holding holding = Holding.firstBuy(
                1L, 1L, new BigDecimal("3"), new BigDecimal("30"),
                new BigDecimal("39000"), NOW);

        holding.subtractSell(BigDecimal.ONE, NOW.plusSeconds(1));

        assertThat(holding.getQuantity()).isEqualByComparingTo("2");
        assertThat(holding.getUsdPurchaseAmount()).isEqualByComparingTo("20.0000000000");
        assertThat(holding.getKrwPurchaseAmount()).isEqualByComparingTo("26000.0000000000000000");
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("10.0000");
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo("1300.000000");
    }

    @Test
    void 전량_매도_후_재매수는_과거_매수금액을_승계하지_않는다() {
        Holding holding = Holding.firstBuy(
                1L, 1L, new BigDecimal("3"), new BigDecimal("30"),
                new BigDecimal("39000"), NOW);
        holding.subtractSell(new BigDecimal("3"), NOW.plusSeconds(1));

        assertThat(holding.getQuantity()).isZero();
        assertThat(holding.getUsdPurchaseAmount()).isZero();
        assertThat(holding.getKrwPurchaseAmount()).isZero();

        holding.addBuy(
                new BigDecimal("2"), new BigDecimal("40"),
                new BigDecimal("56000"), NOW.plusSeconds(2));

        assertThat(holding.getQuantity()).isEqualByComparingTo("2");
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("20.0000");
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo("1400.000000");
        assertThat(holding.getUsdPurchaseAmount()).isEqualByComparingTo("40");
        assertThat(holding.getKrwPurchaseAmount()).isEqualByComparingTo("56000");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1"})
    void 추가_매수_수량은_0보다_커야_한다(String value) {
        Holding holding = holding();
        BigDecimal quantity = value == null ? null : new BigDecimal(value);

        assertThatThrownBy(() -> holding.addBuy(
                quantity, new BigDecimal("100"), new BigDecimal("140000"), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("매수 수량은 0보다 커야 합니다");
        assertUnchanged(holding);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1"})
    void 추가_USD_매수금액은_0_이상이어야_한다(String value) {
        Holding holding = holding();
        BigDecimal usdPurchaseAmount = value == null ? null : new BigDecimal(value);

        assertThatThrownBy(() -> holding.addBuy(
                BigDecimal.ONE, usdPurchaseAmount, new BigDecimal("140000"), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USD 매수금액은 0 이상이어야 합니다");
        assertUnchanged(holding);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1"})
    void 추가_원화_매수금액은_0보다_커야_한다(String value) {
        Holding holding = holding();
        BigDecimal krwPurchaseAmount = value == null ? null : new BigDecimal(value);

        assertThatThrownBy(() -> holding.addBuy(
                BigDecimal.ONE, new BigDecimal("100"), krwPurchaseAmount, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("원화 매수금액은 0보다 커야 합니다");
        assertUnchanged(holding);
    }

    @Test
    void 보유중인_종목과_다른_통화의_매수금액을_섞을_수_없다() {
        Holding holding = holding();

        assertThatThrownBy(() -> holding.addBuy(
                BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("140000"), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("기존 보유 종목과 같은 통화의 매수금액이어야 합니다");
        assertUnchanged(holding);
    }

    private Holding holding() {
        return Holding.firstBuy(
                1L, 1L, new BigDecimal("2"), new BigDecimal("200"),
                new BigDecimal("276000"), NOW);
    }

    private void assertUnchanged(Holding holding) {
        assertThat(holding.getQuantity()).isEqualByComparingTo("2");
        assertThat(holding.getAvgBuyPrice()).isEqualByComparingTo("100");
        assertThat(holding.getAvgExchangeRate()).isEqualByComparingTo("1380");
        assertThat(holding.getUsdPurchaseAmount()).isEqualByComparingTo("200");
        assertThat(holding.getKrwPurchaseAmount()).isEqualByComparingTo("276000");
        assertThat(holding.getUpdatedAt()).isEqualTo(NOW);
    }
}
