package com.baedang.stock.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockTest {

    @Test
    void 종목_심볼은_대문자로_정규화해_저장한다() {
        Stock stock = Stock.create(
                " aapl ", MarketCountry.US, "NASDAQ", "애플", "USD", "STOCK");

        assertThat(stock.getSymbol()).isEqualTo("AAPL");
    }
}
