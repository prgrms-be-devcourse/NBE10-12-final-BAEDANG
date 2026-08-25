package com.baedang.market.client.toss;

import com.baedang.global.client.toss.TossSecuritiesClient;
import com.baedang.market.client.toss.dto.TossCandleResponse;
import com.baedang.market.client.toss.dto.TossPriceResponse;
import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.baedang.global.client.toss.TossPathWhitelist.CANDLES;
import static com.baedang.global.client.toss.TossPathWhitelist.PRICES;

@Component
public class TossMarketDataAdapter implements MarketDataPort {
    private final TossSecuritiesClient tossSecuritiesClient;

    public TossMarketDataAdapter(TossSecuritiesClient tossSecuritiesClient) {
        this.tossSecuritiesClient = tossSecuritiesClient;
    }

    @Override
    public List<PriceQuote> fetchPrices(List<String> symbols) {
        TossPriceResponse response = tossSecuritiesClient.get(
                PRICES,
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

    @Override
    public List<Candle> fetchCandles(String symbol, CandleInterval interval, int count) {
        TossCandleResponse response = tossSecuritiesClient.get(
                CANDLES,
                Map.of(
                        "symbol", symbol,
                        "interval", toTossInterval(interval),
                        "count", String.valueOf(count),
                        "adjusted", "true"
                ),
                TossCandleResponse.class
        );
        return response.result().candles().stream()
                .map(item -> new Candle(
                        item.timestamp(),
                        new BigDecimal(item.openPrice()),
                        new BigDecimal(item.highPrice()),
                        new BigDecimal(item.lowPrice()),
                        new BigDecimal(item.closePrice()),
                        new BigDecimal(item.volume()),
                        item.currency()
                )).toList();
    }

    private String toTossInterval(CandleInterval interval) {
        return switch (interval){
            case ONE_MINUTE -> "1m";
            case ONE_DAY -> "1d";
        };
    }


}
