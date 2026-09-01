package com.baedang.account.dto;

import com.baedang.account.support.HoldingValuation;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingsResponseTest {

    @Test
    void 국내_평단가는_원단위로_미리_반올림하지_않고_소수점4자리까지_보존한다() {
        HoldingValuation valuation = new HoldingValuation(
                1L,
                "KRW",
                new BigDecimal("15"),
                new BigDecimal("71166.6667"),
                new BigDecimal("1.000000"),
                new BigDecimal("72000"),
                new BigDecimal("1080000"),
                new BigDecimal("1067500")
        );
        Stock stock = Stock.create(
                "005930", MarketCountry.KR, "KOSPI", "삼성전자",
                null, "KRW", "STOCK", true
        );

        HoldingsResponse.Item response = HoldingsResponse.Item.of(valuation, stock, true);

        assertThat(response.avgBuyPrice()).isEqualTo("71166.6667");
        assertThat(response.avgExchangeRate()).isEqualTo("1");
        assertThat(response.lastPrice()).isEqualTo("72000");
    }

    @Test
    void 미국_평단가도_센트로_미리_반올림하지_않고_소수점4자리까지_보존한다() {
        HoldingValuation valuation = new HoldingValuation(
                2L,
                "USD",
                new BigDecimal("3"),
                new BigDecimal("88.3456"),
                new BigDecimal("1383.600000"),
                new BigDecimal("90.005"),
                new BigDecimal("373572"),
                new BigDecimal("366685")
        );
        Stock stock = Stock.create(
                "INTC", MarketCountry.US, "NASDAQ", "인텔",
                null, "USD", "STOCK", true
        );

        HoldingsResponse.Item response = HoldingsResponse.Item.of(valuation, stock, true);

        assertThat(response.avgBuyPrice()).isEqualTo("88.3456");
        assertThat(response.avgExchangeRate()).isEqualTo("1383.6");
        assertThat(response.lastPrice()).isEqualTo("90.01");
    }
}
