package com.baedang.stock.client.toss;

import com.baedang.global.clients.toss.TossSecuritiesClient;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.client.toss.dto.TossListedStockResponse;
import com.baedang.stock.client.toss.dto.TossStockInfoResponse;
import com.baedang.stock.client.toss.dto.TossStockWarningResponse;
import com.baedang.stock.port.StockInfo;
import com.baedang.stock.port.StockUniverseEntry;
import com.baedang.stock.port.StockWarnings;
import com.baedang.stock.port.SymbolInfoPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TossSymbolInfoAdapter implements SymbolInfoPort {

    private static final int MAX_SYMBOLS_PER_REQUEST = 200;

    private static final Set<String> TOSS_MARKETS = Set.of("KOSPI", "KOSDAQ", "NYSE", "NASDAQ", "AMEX", "KR_ETC", "US_ETC");

    private final TossSecuritiesClient tossSecuritiesClient;

    public TossSymbolInfoAdapter(TossSecuritiesClient tossSecuritiesClient) {
        this.tossSecuritiesClient = tossSecuritiesClient;
    }

    @Override
    public List<StockInfo> fetchStocks(List<String> symbols) {
        validateSymbols(symbols);

        TossStockInfoResponse response = tossSecuritiesClient.get(
                "/api/v1/stocks",
                Map.of("symbols", String.join(",", symbols)),
                TossStockInfoResponse.class
        );

        if (response == null || response.result() == null) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "종목 정보 응답이 비어 있음");
        }

        return response.result().stream()
                .filter(item -> !"SCHEDULED".equals(item.status()))
                .map(this::toStockInfo)
                .toList();
    }

    @Override
    public StockWarnings fetchStockWarnings(String symbol) {
        validateSymbol(symbol);

        TossStockWarningResponse response = tossSecuritiesClient.get(
                "/api/v1/stocks/" + symbol + "/warnings",
                Map.of(),
                TossStockWarningResponse.class
        );

        if (response == null || response.result() == null) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "유의사항 응답이 비어 있음");
        }

        return new StockWarnings(
                symbol,
                response.result().stream()
                        .map(item -> new StockWarnings.StockWarning(
                                item.warningType(),
                                item.exchange(),
                                item.startDate(),
                                item.endDate()
                        )).toList()
        );
    }

    @Override
    public List<StockUniverseEntry> fetchAllStocks(String market) {
        if (market == null || !TOSS_MARKETS.contains(market)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지원하지 않는 마켓: " + market);
        }

        TossListedStockResponse response = tossSecuritiesClient.get(
                "/api/v1/stocks/all",
                Map.of("market", market),
                TossListedStockResponse.class
        );

        if (response == null || response.result() == null) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR,"종목 유니버스 응답이 비어 있음");
        }

        return response.result().stream()
                .map(item->new StockUniverseEntry(
                        item.symbol(),
                        item.name(),
                        item.securityType(),
                        item.isCommonShare(),
                        item.isinCode()
                )).toList();
    }

    private StockInfo toStockInfo(TossStockInfoResponse.TossStockInfo item) {
        try {
            return new StockInfo(
                    item.symbol(),
                    item.name(),
                    item.englishName(),
                    item.isinCode(),
                    item.market(),
                    item.securityType(),
                    item.isCommonShare(),
                    item.status(),
                    item.currency(),
                    item.listDate(),
                    item.delistDate(),
                    new BigDecimal(item.sharesOutstanding()),
                    parseNullable(item.leverageFactor()),
                    toKrMarketDetail(item.koreanMarketDetail())
            );
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.TOSS_API_ERROR, "종목 수치 형식 오류: " + item.symbol());
        }
    }

    private BigDecimal parseNullable(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private StockInfo.KrMarketDetail toKrMarketDetail(TossStockInfoResponse.KrMarketDetail detail) {
        if (detail == null) return null;

        return new StockInfo.KrMarketDetail(
                Boolean.TRUE.equals(detail.liquidationTrading()),
                Boolean.TRUE.equals(detail.nxtSupported()),
                Boolean.TRUE.equals(detail.krxTradingSuspended()),
                detail.nxtTradingSuspended()   // NXT 미지원 종목은 null 보존
        );
    }

    private void validateSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()
                || symbols.size() > MAX_SYMBOLS_PER_REQUEST
                || symbols.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "종목 심볼은 1~200개여야 함");
        }
    }

    private void validateSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "종목 심볼이 비어 있음");
        }
    }
}