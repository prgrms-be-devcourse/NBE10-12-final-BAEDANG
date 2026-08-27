package com.baedang.market.port;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketSessionStatusTest {

    @Test
    void 열린_세션은_종료시각이_필수다() {
        assertThatThrownBy(() -> new MarketSessionStatus(true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("종료 시각");
    }
}
