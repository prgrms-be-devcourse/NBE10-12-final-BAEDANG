package com.baedang.stock.client.toss;

import com.baedang.global.clients.toss.TossSecuritiesClient;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.client.toss.dto.TossStockInfoResponse;
import com.baedang.stock.client.toss.dto.TossStockWarningResponse;
import com.baedang.stock.port.StockInfo;
import com.baedang.stock.port.StockWarnings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TossSymbolInfoAdapterTest {

    private final TossSecuritiesClient tossSecuritiesClient = mock(TossSecuritiesClient.class);
    private final TossSymbolInfoAdapter tossSymbolInfoAdapter = new TossSymbolInfoAdapter(tossSecuritiesClient);

    @Test
    @DisplayName("국내 종목 응답을 StockInfo로 변환")
    void convertsKrStockResponseToStockInfo() {
        TossStockInfoResponse response = new TossStockInfoResponse(List.of(krStockItem("005930", "ACTIVE")));

        when(tossSecuritiesClient.get(
                eq("/api/v1/stocks"),
                eq(Map.of("symbols", "005930")),
                eq(TossStockInfoResponse.class)
        )).thenReturn(response);

        List<StockInfo> result = tossSymbolInfoAdapter.fetchStocks(List.of("005930"));

        assertThat(result).hasSize(1);
        StockInfo stockInfo = result.get(0);
        assertThat(stockInfo.symbol()).isEqualTo("005930");
        assertThat(stockInfo.name()).isEqualTo("삼성전자");
        assertThat(stockInfo.status()).isEqualTo("ACTIVE");
        assertThat(stockInfo.sharesOutstanding()).isEqualByComparingTo(new BigDecimal("5919637922"));
        assertThat(stockInfo.leverageFactor()).isNull();

        assertThat(stockInfo.krMarketDetail()).isNotNull();
        assertThat(stockInfo.krMarketDetail().liquidationTrading()).isFalse();
        assertThat(stockInfo.krMarketDetail().nxtSupported()).isTrue();
        assertThat(stockInfo.krMarketDetail().krxTradingSuspended()).isFalse();
        assertThat(stockInfo.krMarketDetail().nxtTradingSuspended()).isNull();
    }

    @Test
    @DisplayName("미국 종목은 koreanMarketDetail이 null로 매핑된다")
    void mapUsStockWithNull() {
        TossStockInfoResponse response = new TossStockInfoResponse(List.of(usStockItem("AAPL")));

        when(tossSecuritiesClient.get(
                eq("/api/v1/stocks"),
                eq(Map.of("symbols", "AAPL")),
                eq(TossStockInfoResponse.class)
        )).thenReturn(response);

        List<StockInfo> result = tossSymbolInfoAdapter.fetchStocks(List.of("AAPL"));

        assertThat(result).hasSize(1);
        StockInfo stockInfo = result.get(0);
        assertThat(stockInfo.symbol()).isEqualTo("AAPL");
        assertThat(stockInfo.currency()).isEqualTo("USD");
        assertThat(stockInfo.krMarketDetail()).isNull();
    }

    @Test
    @DisplayName("상장예정(SCHEDULED)은 제외되고 DELISTED는 유지된다")
    void filterScheduleButKeepDelisted() {
        TossStockInfoResponse response = new TossStockInfoResponse(List.of(
                krStockItem("005930", "ACTIVE"),
                krStockItem("999999", "SCHEDULED"),
                krStockItem("000000", "DELISTED")
        ));

        when(tossSecuritiesClient.get(
                eq("/api/v1/stocks"),
                eq(Map.of("symbols", "005930")),
                eq(TossStockInfoResponse.class)
        )).thenReturn(response);

        List<StockInfo> result = tossSymbolInfoAdapter.fetchStocks(List.of("005930"));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(StockInfo::status).containsExactly("ACTIVE", "DELISTED");
    }

    @Test
    @DisplayName("유의사항 응답을 StockWarnings로 변환하고 미지정 코드도 보존한다")
    void convertsWarningResponse() {
        TossStockWarningResponse response = new TossStockWarningResponse(List.of(
                new TossStockWarningResponse.TossWarningItem(
                        "OVERHEATED", "KRX",
                        LocalDate.of(2026, 8, 20),
                        LocalDate.of(2026, 8, 27)
                ),
                new TossStockWarningResponse.TossWarningItem(
                        "BRAND_NEW_TYPE", null, null, null
                )
        ));

        when(tossSecuritiesClient.get(
                eq("/api/v1/stocks/005930/warnings"),
                eq(Map.of()),
                eq(TossStockWarningResponse.class)
        )).thenReturn(response);

        StockWarnings result = tossSymbolInfoAdapter.fetchStockWarnings("005930");

        assertThat(result.symbol()).isEqualTo("005930");
        assertThat(result.warnings()).hasSize(2);
        assertThat(result.warnings().get(0).warningType()).isEqualTo("OVERHEATED");
        assertThat(result.warnings().get(1).warningType()).isEqualTo("BRAND_NEW_TYPE");
        assertThat(result.warnings().get(1).exchange()).isNull();
    }

    @Test
    @DisplayName("경고가 없으면 빈 종모을 반환한다 - 에러 아님")
    void retunrsEmptyListWhenNoWarnings() {
        TossStockWarningResponse response = new TossStockWarningResponse(List.of());

        when(tossSecuritiesClient.get(
                eq("/api/v1/stocks/AAPL/warnings"),
                eq(Map.of()),
                eq(TossStockWarningResponse.class)
        )).thenReturn(response);

        StockWarnings result = tossSymbolInfoAdapter.fetchStockWarnings("AAPL");

        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("심볼이 200개를 넘으면 예외가 발생한다")
    void rejectsMoreThan200Symbols() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 1; i <= 201; i++) {
            tooMany.add(String.format("%06d", i));
        }

        assertThatThrownBy(() -> tossSymbolInfoAdapter.fetchStocks(tooMany))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("빈 심볼로 경고 조회하면 예외 발생")
    void rejectBlankSymbolForWarning() {
        assertThatThrownBy(() -> tossSymbolInfoAdapter.fetchStockWarnings(" "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    private TossStockInfoResponse.TossStockInfo krStockItem(String symbol, String status) {
        return new TossStockInfoResponse.TossStockInfo(
                symbol,
                "삼성전자",
                "SamsungElec",
                "KR7005930003",
                "KOSPI",
                "STOCK",
                true,
                status,
                "KRW",
                LocalDate.of(1975, 6, 11),
                null,
                "5919637922",
                null,
                new TossStockInfoResponse.KrMarketDetail(
                        false, true, false, null
                )
        );
    }
    private TossStockInfoResponse.TossStockInfo usStockItem(String symbol) {
        return new TossStockInfoResponse.TossStockInfo(
                symbol,
                "애플",
                "APPLE INC",
                "US0378331005",
                "NASDAQ",
                "STOCK",
                true,
                "ACTIVE",
                "USD",
                LocalDate.of(1980, 12, 12),
                null,
                "14702703000",
                null,
                null
        );

    }
}
