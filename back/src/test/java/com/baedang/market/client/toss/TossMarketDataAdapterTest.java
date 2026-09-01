package com.baedang.market.client.toss;

import com.baedang.global.clients.toss.TossSecuritiesClient;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.client.toss.dto.TossCandleResponse;
import com.baedang.market.client.toss.dto.TossPriceResponse;
import com.baedang.market.port.Candle;
import com.baedang.market.port.CandleInterval;
import com.baedang.market.port.PriceQuote;
import com.baedang.stock.client.toss.TossSymbolInfoAdapter;
import com.baedang.stock.client.toss.dto.TossListedStockResponse;
import com.baedang.stock.port.StockUniverseEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
public class TossMarketDataAdapterTest {
    private final TossSecuritiesClient tossSecuritiesClient = mock(TossSecuritiesClient.class);
    private final TossMarketDataAdapter tossMarketDataAdapter = new TossMarketDataAdapter(tossSecuritiesClient);
    private final TossSymbolInfoAdapter tossSymbolInfoAdapter = new  TossSymbolInfoAdapter(tossSecuritiesClient);

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
                eq("/api/v1/prices"),
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
                eq("/api/v1/prices"),
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

    @Test
    @DisplayName("1일봉 응답을 Candle로 변환한다")
    void convertsOneDayCandleResponse() {
        OffsetDateTime candleAt = OffsetDateTime.parse(
                "2026-03-25T09:00:00+09:00"
        );

        TossCandleResponse response = new TossCandleResponse(
                new TossCandleResponse.TossCandleResult(
                        List.of(
                                candleItem(
                                        candleAt,
                                        "71600",
                                        "72300",
                                        "71500",
                                        "72000",
                                        "3521000",
                                        "KRW"
                                )
                        ),
                        null
                )
        );

        when(tossSecuritiesClient.get(
                eq("/api/v1/candles"),
                eq(Map.of(
                        "symbol", "005930",
                        "interval", "1d",
                        "count", "20",
                        "adjusted", "true"
                )),
                eq(TossCandleResponse.class)
        )).thenReturn(response);

        List<Candle> result =
                tossMarketDataAdapter.fetchCandles(
                        "005930",
                        CandleInterval.ONE_DAY,
                        20
                );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).candleAt()).isEqualTo(candleAt);
        assertThat(result.get(0).closePrice())
                .isEqualByComparingTo(new BigDecimal("72000"));
        assertThat(result.get(0).volume())
                .isEqualByComparingTo(new BigDecimal("3521000"));
    }

    @Test
    @DisplayName("200개가 넘는 캔들은 before로 나누어 조회하고 중복을 제거한다")
    void fetchesCandlePagesAndRemovesBoundaryDuplicate() {
        OffsetDateTime firstAt = OffsetDateTime.parse(
                "2026-03-26T15:30:00+09:00"
        );

        List<TossCandleResponse.TossCandleItem> firstItems =
                createMinuteItems(firstAt, 200);

        OffsetDateTime boundary =
                firstItems.get(firstItems.size() - 1).timestamp();

        List<TossCandleResponse.TossCandleItem> secondItems =
                new ArrayList<>();

        // before가 inclusive이므로 첫 번째 페이지의 경계 봉이 중복된다.
        secondItems.add(firstItems.get(firstItems.size() - 1));
        secondItems.addAll(
                createMinuteItems(boundary.minusMinutes(1), 50)
        );

        TossCandleResponse firstResponse =
                new TossCandleResponse(
                        new TossCandleResponse.TossCandleResult(
                                firstItems,
                                boundary
                        )
                );

        TossCandleResponse secondResponse =
                new TossCandleResponse(
                        new TossCandleResponse.TossCandleResult(
                                secondItems,
                                null
                        )
                );

        when(tossSecuritiesClient.get(
                eq("/api/v1/candles"),
                eq(Map.of(
                        "symbol", "005930",
                        "interval", "1m",
                        "count", "200",
                        "adjusted", "true"
                )),
                eq(TossCandleResponse.class)
        )).thenReturn(firstResponse);

        when(tossSecuritiesClient.get(
                eq("/api/v1/candles"),
                eq(Map.of(
                        "symbol", "005930",
                        "interval", "1m",
                        "count", "51",
                        "adjusted", "true",
                        "before", boundary.toString()
                )),
                eq(TossCandleResponse.class)
        )).thenReturn(secondResponse);

        List<Candle> result =
                tossMarketDataAdapter.fetchCandles(
                        "005930",
                        CandleInterval.ONE_MINUTE,
                        250
                );

        assertThat(result).hasSize(250);
        assertThat(result)
                .extracting(Candle::candleAt)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("캔들 200개 요청은 첫 응답으로 충족되면 외부 호출 한 번으로 종료한다")
    void fetchesTwoHundredCandlesWithSingleRequest() {
        OffsetDateTime candleAt = OffsetDateTime.parse("2026-03-26T09:00:00+09:00");
        List<TossCandleResponse.TossCandleItem> items = createMinuteItems(candleAt, 200);
        TossCandleResponse response = new TossCandleResponse(
                new TossCandleResponse.TossCandleResult(
                        items,
                        items.get(items.size() - 1).timestamp()
                )
        );
        Map<String, String> query = Map.of(
                "symbol", "005930",
                "interval", "1d",
                "count", "200",
                "adjusted", "true"
        );
        when(tossSecuritiesClient.get(
                eq("/api/v1/candles"),
                eq(query),
                eq(TossCandleResponse.class)
        )).thenReturn(response);

        List<Candle> result = tossMarketDataAdapter.fetchCandles(
                "005930", CandleInterval.ONE_DAY, 200);

        assertThat(result).hasSize(200);
        verify(tossSecuritiesClient, times(1)).get(
                eq("/api/v1/candles"),
                eq(query),
                eq(TossCandleResponse.class));
    }

    @Test
    @DisplayName("마켓별 전체 종목 유니버스 조회")
    void fetchStockUniverse(){
        TossListedStockResponse response = new TossListedStockResponse(
                List.of(
                        new TossListedStockResponse.TossListedStockItem(
                                "005930", "삼성전자", "STOCK", true, "KR7005930003"
                        ),
                        new TossListedStockResponse.TossListedStockItem(
                                "069500", "KODEX 200", "ETF", true, "KR7069500007"
                        )
                )
        );

        when(tossSecuritiesClient.get(
                eq("/api/v1/stocks/all"),
                eq(Map.of("market","KOSPI")),
                eq(TossListedStockResponse.class)
        )).thenReturn(response);

        List<StockUniverseEntry> result = tossSymbolInfoAdapter.fetchAllStocks("KOSPI");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).symbol()).isEqualTo("005930");
        assertThat(result.get(0).isCommonShare()).isTrue();
        assertThat(result.get(1).securityType()).isEqualTo("ETF");
    }

    @Test
    @DisplayName("지원하지 않는 마켓이면 예외가 발생")
    void rejectUnsupportedMarket(){
        assertThatThrownBy(()->tossSymbolInfoAdapter.fetchAllStocks("TOKYO"))
                .isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("null 마켓이면 예외 발생")
    void rejectNullMarket(){
        assertThatThrownBy(()->tossSymbolInfoAdapter.fetchAllStocks(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    private TossCandleResponse.TossCandleItem candleItem(
            OffsetDateTime timestamp,
            String openPrice,
            String highPrice,
            String lowPrice,
            String closePrice,
            String volume,
            String currency
    ) {
        return new TossCandleResponse.TossCandleItem(
                timestamp,
                openPrice,
                highPrice,
                lowPrice,
                closePrice,
                volume,
                currency
        );
    }

    private List<TossCandleResponse.TossCandleItem>
    createMinuteItems(
            OffsetDateTime start,
            int count
    ) {
        return IntStream.range(0, count)
                .mapToObj(index -> candleItem(
                        start.minusMinutes(index),
                        "72000",
                        "72100",
                        "71950",
                        "72050",
                        "15200",
                        "KRW"
                ))
                .toList();
    }
}
