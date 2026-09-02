package com.baedang.global.clients.toss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class WhitelistTest {
    @ParameterizedTest
    @CsvSource({
            "/api/v1/exchange-rate, MARKET_INFO",
            "/api/v1/market-calendar/KR, MARKET_INFO",
            "/api/v1/market-calendar/US, MARKET_INFO",
            "/api/v1/prices, MARKET_DATA",
            "/api/v1/candles, MARKET_DATA_CHART",
            "/api/v1/stocks, STOCK",
            "/api/v1/stocks/all, STOCK_ALL",
            "/api/v1/stocks/005930/warnings, STOCK",
            "/api/v1/rankings, RANKING"
    })
    @DisplayName("허용 경로는 올바른 API 그룹으로 분류된다")
    void t1(String path, TossApiGroup group) {
        assertThat(Whitelist.resolve(path).group()).isEqualTo(group);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/orders", "/api/v1/orders/12345", "/api/v1/unknown"})
    @DisplayName("주문, 미등록 경로는 분류되지 않는다")
    void t2(String path) {
        assertThat(Whitelist.resolve(path)).isNull();
    }
}
