package com.baedang.market.client.toss;

import com.baedang.global.clients.toss.TossSecuritiesClient;
import com.baedang.market.client.toss.dto.TossPriceLimitResponse;
import com.baedang.market.port.PriceLimits;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TossPriceLimitsAdapterTest {
    private final TossSecuritiesClient client = mock(TossSecuritiesClient.class);
    private final TossMarketDataAdapter adapter = new TossMarketDataAdapter(client);

    @Test
    void 공식_필드명과_단일_symbol_파라미터를_사용한다() {
        when(client.get("/api/v1/price-limits", Map.of("symbol", "005930"), TossPriceLimitResponse.class))
                .thenReturn(new TossPriceLimitResponse(new TossPriceLimitResponse.Result(
                        OffsetDateTime.parse("2026-09-11T09:00:00+09:00"), "93000", "50400", "KRW")));
        PriceLimits result = adapter.fetchPriceLimits("005930");
        assertThat(result.upperLimit()).isEqualByComparingTo("93000");
        assertThat(result.lowerLimit()).isEqualByComparingTo("50400");
    }

    @Test
    void 미국_null_가격은_정상_응답이다() {
        when(client.get("/api/v1/price-limits", Map.of("symbol", "AAPL"), TossPriceLimitResponse.class))
                .thenReturn(new TossPriceLimitResponse(new TossPriceLimitResponse.Result(
                        OffsetDateTime.parse("2026-09-11T09:00:00-04:00"), null, null, "USD")));
        PriceLimits result = adapter.fetchPriceLimits("AAPL");
        assertThat(result.upperLimit()).isNull();
        assertThat(result.lowerLimit()).isNull();
        assertThat(result.currency()).isEqualTo("USD");
    }

    @Test
    void 빈응답은_실패한다() {
        assertThatThrownBy(() -> adapter.fetchPriceLimits("005930")).isInstanceOf(RuntimeException.class);
    }
}
