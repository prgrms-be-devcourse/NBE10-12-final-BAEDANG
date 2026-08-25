package com.baedang.market.client.toss;

import com.baedang.global.client.toss.TossSecuritiesClient;
import com.baedang.market.client.toss.dto.TossPriceResponse;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
public class TossMarketDataAdapter implements MarketDataPort {
    private final TossSecuritiesClient tossSecuritiesClient;

    public TossMarketDataAdapter(TossSecuritiesClient tossSecuritiesClient){
        this.tossSecuritiesClient = tossSecuritiesClient;
    }

    @Override
    public List<PriceQuote> fetchPrices(List<String> symbols) {
        TossPriceResponse response = tossSecuritiesClient.get(
                "/api/v1/prices",
                Map.of("symbols", String.join(",", symbols)),
                TossPriceResponse.class
        );

        return response.result().stream()
                .map(item -> new PriceQuote(
                        item.symbol(),
                        new BigDecimal(item.lastPrice()),
                        item.timestamp(),
                        item.currency()
                )).toList();
    }


}
