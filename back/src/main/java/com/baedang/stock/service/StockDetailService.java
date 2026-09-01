package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.dto.StockDetailResponse;
import com.baedang.stock.entity.ListingStatus;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static com.baedang.global.formatter.FinancialDecimalFormatter.currency;
import static com.baedang.global.formatter.FinancialDecimalFormatter.plain;

@Service
public class StockDetailService {

    private static final StockDetailResponse.Warning INVESTMENT_WARNING =
            new StockDetailResponse.Warning("INVESTMENT_WARNING", "투자경고");

    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final QuoteRealtimePolicy quoteRealtimePolicy;

    public StockDetailService(
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            QuoteRealtimePolicy quoteRealtimePolicy
    ) {
        this.stockRepository = stockRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.quoteRealtimePolicy = quoteRealtimePolicy;
    }

    public StockDetailResponse getDetail(String symbol, String marketCountryValue) {
        MarketCountry marketCountry = parseMarketCountry(marketCountryValue);
        Stock stock = stockRepository.findBySymbolIgnoreCaseAndMarketCountry(symbol, marketCountry)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STOCK_NOT_FOUND,
                        "symbol=" + symbol + ", marketCountry=" + marketCountry));
        QuoteSnapshot quote = quoteSnapshotRepository.findById(stock.getStockId()).orElse(null);

        boolean realtime = Boolean.TRUE.equals(stock.getIsRanked())
                && quoteRealtimePolicy.isRealtime(marketCountry, quote);
        Tradability tradability = tradability(stock, quote);

        return new StockDetailResponse(
                stock.getSymbol(),
                stock.getName(),
                stock.getEnglishName(),
                stock.getMarket(),
                stock.getMarketCountry(),
                stock.getCurrency(),
                stock.getIsinCode(),
                stock.getStockCategory(),
                plain(stock.getLeverageFactor()),
                stock.getIsDividend(),
                price(quote, realtime, stock.getCurrency()),
                info(stock, quote),
                Boolean.TRUE.equals(stock.getIsWarned()) ? List.of(INVESTMENT_WARNING) : List.of(),
                tradability.tradable(),
                tradability.reason()
        );
    }

    private Tradability tradability(Stock stock, QuoteSnapshot quote) {
        if (!Boolean.TRUE.equals(stock.getIsRanked()) || stock.getListingStatus() != ListingStatus.ACTIVE) {
            return Tradability.rejected("NOT_IN_UNIVERSE");
        }
        if (Boolean.TRUE.equals(stock.getIsSuspended())) return Tradability.rejected("SUSPENDED");
        if (Boolean.TRUE.equals(stock.getIsLiquidation())) return Tradability.rejected("LIQUIDATION");
        if (!quoteRealtimePolicy.isMarketOpen(stock.getMarketCountry())) {
            return Tradability.rejected("MARKET_CLOSED");
        }
        if (quote == null) return Tradability.rejected("QUOTE_NOT_FOUND");
        return new Tradability(true, null);
    }

    private StockDetailResponse.Price price(QuoteSnapshot quote, boolean realtime, String currencyCode) {
        if (quote == null) {
            return new StockDetailResponse.Price(null, null, null, null, null, null, null, false);
        }
        BigDecimal changeAmount = quote.getPrevClose() == null
                ? null
                : quote.getLastPrice().subtract(quote.getPrevClose());
        return new StockDetailResponse.Price(
                currency(quote.getLastPrice(), currencyCode),
                currency(quote.getPrevClose(), currencyCode),
                currency(changeAmount, currencyCode),
                plain(quote.changeRate()),
                currency(quote.getUpperLimit(), currencyCode),
                currency(quote.getLowerLimit(), currencyCode),
                quote.getQuoteAt(),
                realtime
        );
    }

    private StockDetailResponse.Info info(Stock stock, QuoteSnapshot quote) {
        BigDecimal marketCap = quote == null || quote.getLastPrice() == null || stock.getSharesOutstanding() == null
                ? null
                : quote.getLastPrice().multiply(stock.getSharesOutstanding());
        return new StockDetailResponse.Info(
                currency(marketCap, stock.getCurrency()),
                plain(stock.getSharesOutstanding()),
                stock.getListDate()
        );
    }

    private MarketCountry parseMarketCountry(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "marketCountry는 KR 또는 US여야 합니다");
        }
        try {
            return MarketCountry.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "marketCountry는 KR 또는 US여야 합니다");
        }
    }

    private record Tradability(boolean tradable, String reason) {
        private static Tradability rejected(String reason) {
            return new Tradability(false, reason);
        }
    }
}
