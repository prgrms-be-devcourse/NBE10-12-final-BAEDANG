package com.baedang.orderbook.service;

import com.baedang.orderbook.model.StockDescriptor;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TickSizePolicyTest {

    private final TickSizePolicy policy = new TickSizePolicy();

    private static StockDescriptor krIndividual() {
        return new StockDescriptor(1L, MarketCountry.KR, StockCategory.INDIVIDUAL, "KRW");
    }

    private static StockDescriptor krPreferred() {
        return new StockDescriptor(2L, MarketCountry.KR, StockCategory.PREFERRED, "KRW");
    }

    private static StockDescriptor krEtf() {
        return new StockDescriptor(3L, MarketCountry.KR, StockCategory.ETF, "KRW");
    }

    private static StockDescriptor krEtn() {
        return new StockDescriptor(4L, MarketCountry.KR, StockCategory.ETN, "KRW");
    }

    private static StockDescriptor usIndividual() {
        return new StockDescriptor(5L, MarketCountry.US, StockCategory.INDIVIDUAL, "USD");
    }

    @ParameterizedTest
    @CsvSource({
            "1999,2000,1998",
            "2000,2005,1999",
            "4999,5000,4995",
            "5000,5010,4995",
            "19990,20000,19980",
            "20000,20050,19990",
            "49950,50000,49900",
            "50000,50100,49950",
            "199900,200000,199800",
            "200000,200500,199900",
            "499500,500000,499000",
            "500000,501000,499500"
    })
    void 국내_일반주_구간경계의_다음과_이전_가격을_계산한다(String current, String next, String previous) {
        StockDescriptor stock = krIndividual();
        assertThat(policy.nextValidPriceAbove(stock, new BigDecimal(current))).isEqualByComparingTo(next);
        assertThat(policy.previousValidPriceBelow(stock, new BigDecimal(current))).isEqualByComparingTo(previous);
    }

    @ParameterizedTest
    @CsvSource({"1999,2000,1998", "2000,2005,1999"})
    void 국내_ETF_2000원_경계를_계산한다(String current, String next, String previous) {
        StockDescriptor stock = krEtf();
        assertThat(policy.nextValidPriceAbove(stock, new BigDecimal(current))).isEqualByComparingTo(next);
        assertThat(policy.previousValidPriceBelow(stock, new BigDecimal(current))).isEqualByComparingTo(previous);
    }

    @Test
    void 미국_1달러_경계를_계산한다() {
        StockDescriptor stock = usIndividual();
        assertThat(policy.nextValidPriceAbove(stock, new BigDecimal("0.9999"))).isEqualByComparingTo("1.0000");
        assertThat(policy.previousValidPriceBelow(stock, new BigDecimal("1.0000"))).isEqualByComparingTo("0.9999");
        assertThat(policy.nextValidPriceAbove(stock, new BigDecimal("1.0000"))).isEqualByComparingTo("1.0100");
    }

    @ParameterizedTest
    @CsvSource({
            // 국내 일반주·ETF는 2,000원 이상 구간이 5원 단위 — 2,003원은 유효 호가가 아니다.
            "KR_개인,2003",
            "KR_ETF,2003",
            // 1,500원 미만 구간은 1원 단위라 소수 가격은 유효하지 않다.
            "KR_개인,1500.5",
            // 미국 1달러 미만 구간은 0.0001 단위 — 다섯째 자리 가격은 유효하지 않다.
            "US,0.99995"
    })
    void 유효하지_않은_가격을_감별한다(String kind, String price) {
        StockDescriptor stock = switch (kind) {
            case "KR_개인" -> krIndividual();
            case "KR_ETF" -> krEtf();
            case "US" -> usIndividual();
            default -> throw new IllegalArgumentException(kind);
        };
        assertThat(policy.isValidPrice(stock, new BigDecimal(price))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "KR_개인,1999",
            "KR_개인,2005",
            "KR_개인,5010",
            "KR_개인,501000",
            "KR_ETF,1999",
            "US,1.01"
    })
    void 유효한_가격은_구간_단위를_따른다(String kind, String price) {
        StockDescriptor stock = switch (kind) {
            case "KR_개인" -> krIndividual();
            case "KR_ETF" -> krEtf();
            case "US" -> usIndividual();
            default -> throw new IllegalArgumentException(kind);
        };
        assertThat(policy.isValidPrice(stock, new BigDecimal(price))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"1999,2000", "2005,2010", "5010,5020", "501000,502000"})
    void 우선주는_일반주와_같은_단위를_쓴다(String price, String next) {
        assertThat(policy.nextValidPriceAbove(krPreferred(), new BigDecimal(price))).isEqualByComparingTo(next);
    }

    @Test
    void 국내_ETN은_ETF와_같은_단위를_쓴다() {
        assertThat(policy.nextValidPriceAbove(krEtn(), new BigDecimal("1999"))).isEqualByComparingTo("2000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void 음수나_0은_거절한다(String price) {
        StockDescriptor stock = krIndividual();
        BigDecimal value = new BigDecimal(price);
        assertThatThrownBy(() -> policy.nextValidPriceAbove(stock, value))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.previousValidPriceBelow(stock, value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 저가_종목은_이전_유효_가격이_없으면_거절한다() {
        // 1원 미만 구간이 없으므로 1원 아래 유효 호가는 존재하지 않는다.
        assertThatThrownBy(() -> policy.previousValidPriceBelow(krIndividual(), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
