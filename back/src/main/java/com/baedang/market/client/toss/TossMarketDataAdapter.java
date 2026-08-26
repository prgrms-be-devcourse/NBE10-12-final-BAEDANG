package com.baedang.market.client.toss;

import com.baedang.global.client.toss.TossSecuritiesClient;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.client.toss.dto.TossCandleResponse;
import com.baedang.market.client.toss.dto.TossPriceResponse;
import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import org.springframework.stereotype.Component;
// pr#11 반영
// import com.baedang.global.clients.tossSecurities.TossSecuritiesClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.baedang.global.client.toss.TossPathWhitelist.CANDLES;
import static com.baedang.global.client.toss.TossPathWhitelist.PRICES;

@Component
public class TossMarketDataAdapter implements MarketDataPort {

    private static final int MAX_PRICE_SYMBOLS_PER_REQUEST = 200;
    private static final int MAX_CANDLES_PER_REQUEST = 200;

    private final TossSecuritiesClient tossSecuritiesClient;

    public TossMarketDataAdapter(TossSecuritiesClient tossSecuritiesClient) {
        this.tossSecuritiesClient = tossSecuritiesClient;
    }

    @Override
    public List<PriceQuote> fetchPrices(List<String> symbols) {
        validateSymbols(symbols);
        TossPriceResponse response = tossSecuritiesClient.get(
                PRICES,
                // pr#11 반영
                // "/api/v1/prices",
                Map.of("symbols", String.join(",", symbols)),
                TossPriceResponse.class
        );

        if (response == null || response.result() == null) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "현재가 응답이 비어 있음");
        }

        return response.result().stream()
                .map(this::toPriceQuote).toList();
    }

    @Override
    public List<Candle> fetchCandles(String symbol, CandleInterval interval, int count) {
        validateCandleRequest(symbol, interval, count);

        Map<OffsetDateTime, Candle> collected = new LinkedHashMap<>();
        OffsetDateTime before = null;

        while (collected.size() < count) {
            int remaining = count - collected.size();
            int requestCount = before == null
                    ? Math.min(remaining, MAX_CANDLES_PER_REQUEST)
                    : Math.min(remaining + 1, MAX_CANDLES_PER_REQUEST);

            Map<String, String> queryParams = new LinkedHashMap<>();
            queryParams.put("symbol", symbol);
            queryParams.put("interval", toTossInterval(interval));
            queryParams.put("count", String.valueOf(requestCount));
            queryParams.put("adjusted", "true");

            if (before != null) queryParams.put("before", before.toString());

            TossCandleResponse response = tossSecuritiesClient.get(
                    CANDLES,
                    // pr#11 반영
                    // "/api/v1/candles",
                    Map.copyOf(queryParams),
                    TossCandleResponse.class
            );

            TossCandleResponse.TossCandleResult result = requireCandleResult(response);

            if (result.candles() == null || result.candles().isEmpty()) break;

            for (TossCandleResponse.TossCandleItem item : result.candles()) {
                Candle candle = toCandle(item);
                collected.putIfAbsent(candle.candleAt(), candle);

                if (collected.size() == count) break;
            }

            OffsetDateTime nextBefore = result.nextBefore();

            if (nextBefore == null || nextBefore.equals(before)) break;

            before = nextBefore;
        }
        return collected.values().stream().limit(count).toList();
    }

    private PriceQuote toPriceQuote(TossPriceResponse.TossPriceItem item) {
        try {
            return new PriceQuote(
                    item.symbol(),
                    new BigDecimal(item.lastPrice()),
                    item.timestamp(),
                    item.currency()
            );
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "현재가 형식 오류: " + item.symbol());
        }
    }

    private Candle toCandle(TossCandleResponse.TossCandleItem item) {
        if (item.timestamp() == null) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "캔들 timestamp가 비어 있음");
        }
        try {
            return new Candle(
                    item.timestamp(),
                    new BigDecimal(item.openPrice()),
                    new BigDecimal(item.highPrice()),
                    new BigDecimal(item.lowPrice()),
                    new BigDecimal(item.closePrice()),
                    new BigDecimal(item.volume()),
                    item.currency()
            );
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "캔들 금액 형식 오류: " + item.timestamp());
        }
    }

    private TossCandleResponse.TossCandleResult requireCandleResult(TossCandleResponse response) {
        if (response == null || response.result() == null) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "캔들 응답이 비어 있음");
        }
        return response.result();
    }

    private void validateSymbols(List<String> symbols) {
        if (symbols == null
                || symbols.isEmpty()
                || symbols.size() > MAX_PRICE_SYMBOLS_PER_REQUEST
                || symbols.stream().anyMatch(
                        symbol -> symbol == null || symbol.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"현재가 심볼은 1~200개여아 함");
        }
    }

    private void validateCandleRequest(String symbol, CandleInterval interval, int count) {
        if (symbol == null || symbol.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"캔들 종목 심볼이 비어 있음");
        }

        if (interval == null || count <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,"캔들 interval 또는 count가 올바르지 않음");
        }
    }

    private String toTossInterval(CandleInterval interval) {
        return switch (interval) {
            case ONE_MINUTE -> "1m";
            case ONE_DAY -> "1d";
        };
    }


}
