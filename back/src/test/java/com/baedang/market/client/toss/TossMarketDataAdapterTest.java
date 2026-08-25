package com.baedang.market.client.toss;

import com.baedang.market.client.toss.dto.TossPriceResponse;
import com.baedang.market.port.PriceQuote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
public class TossMarketDataAdapterTest {
    private static final String PRICES_PATH = "/api/v1/prices";
    private final TossSecuritiesClient tossSecuritiesClient = mock(TossSecuritiesClient.class);
    private final TossMarketDataAdapter tossMarketDataAdapter = new TossMarketDataAdapter(tossSecuritiesClient);

    @Test
    @DisplayName("현재가 응답을 PriceQuote로 변환")
    void convertsPriceResponseToPriceQuote() {
        OffsetDateTime quoteAt = OffsetDateTime.parse("2026-03-26T09:30:00.123+09:00");
        TossPriceResponse response = new TossPriceResponse(
                List.of(
                        new TossPriceResponse.TossPriceItem(
                                "005930",
                                quoteAt,
                                "72000",
                                "KRW"
                        )
                )
        );

        when(tossSecuritiesClient.get(
                eq(PRICES_PATH),
                eq(Map.of("symbols","005930")),
                eq(TossPriceResponse.class)
        )).thenReturn(response);

        List<PriceQuote> result = tossMarketDataAdapter.fetchPrices(List.of("005930"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("005930");
        assertThat(result.get(0).lastPrice()).isEqualByComparingTo(new BigDecimal("72000"));
        assertThat(result.get(0).quoteAt()).isEqualTo(quoteAt);
        assertThat(result.get(0).currency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("timestamp가 null인 현재가도 반환")
    void convertsPriceResponseToPriceQuoteNull() {
        TossPriceResponse response = new TossPriceResponse(
                List.of(
                        new TossPriceResponse.TossPriceItem(
                                "AAPL",
                                null,
                                "185.70",
                                "USD"
                        )
                )
        );

        when(tossSecuritiesClient.get(
                eq(PRICES_PATH),
                eq(Map.of("symbols","AAPL")),
                eq(TossPriceResponse.class)
        )).thenReturn(response);

        List<PriceQuote> result = tossMarketDataAdapter.fetchPrices(List.of("AAPL"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(0).lastPrice()).isEqualByComparingTo(new BigDecimal("185.70"));
        assertThat(result.get(0).quoteAt()).isNull();
        assertThat(result.get(0).currency()).isEqualTo("USD");
    }
}
