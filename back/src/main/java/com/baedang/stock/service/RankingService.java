package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketSessionProvider;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.dto.RankingResponse;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class RankingService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int MAX_CURSOR_LENGTH = 128;

    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final MarketSessionProvider marketSessionProvider;
    private final Clock clock;

    public RankingService(
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            MarketSessionProvider marketSessionProvider,
            Clock clock
    ) {
        this.stockRepository = stockRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.marketSessionProvider = marketSessionProvider;
        this.clock = clock;
    }

    public RankingResponse getRankings(
            String market,
            int size,
            String cursor
    ) {
        MarketCountry marketCountry = parseMarket(market);
        validateSize(size);

        List<Stock> stocks = findStocks(
                marketCountry,
                size,
                cursor
        );

        boolean hasNext = stocks.size() > size;

        if (hasNext) stocks = stocks.subList(0, size);

        Map<Long, QuoteSnapshot> quotes = quotesByStockId(stocks);

        List<RankingResponse.Item> items = stocks.stream()
                .map(stock -> toItem(
                        stock,
                        quotes.get(stock.getStockId())
                )).toList();

        String nextCursor = null;

        if (hasNext && !stocks.isEmpty()) {
            Stock last = stocks.get(stocks.size() - 1);

            nextCursor = encodeCursor(
                    last.getTradingAmount(),
                    last.getStockId()
            );
        }

        return new RankingResponse(
                items,
                nextCursor,
                hasNext
        );
    }

    private List<Stock> findStocks(
            MarketCountry marketCountry,
            int size,
            String cursor
    ) {
        PageRequest pageable = PageRequest.of(0, size + 1);
        if (cursor == null || cursor.isBlank()) {
            return stockRepository.findRankedByMarketCountry(marketCountry, pageable);
        }

        RankingCursor decoded = decodeCursor(cursor);

        return stockRepository.findRankedAfterCursor(
                marketCountry,
                decoded.tradingAmount(),
                decoded.stockId(),
                pageable
        );
    }

    private Map<Long, QuoteSnapshot> quotesByStockId(List<Stock> stocks) {
        if (stocks.isEmpty()) return Map.of();

        List<Long> stockIds = stocks.stream()
                .map(Stock::getStockId).toList();

        return quoteSnapshotRepository.findByStockIdIn(stockIds)
                .stream()
                .collect(
                        HashMap::new,
                        (m, q) -> m.put(q.getStockId(), q),
                        HashMap::putAll
                );
    }

    private RankingResponse.Item toItem(Stock stock, QuoteSnapshot quote) {
        BigDecimal lastPrice = quote == null ? null : quote.getLastPrice();
        BigDecimal prevClose = quote == null ? null : quote.getPrevClose();
        BigDecimal changeAmount = calculateChangeAmount(lastPrice, prevClose);
        BigDecimal changeRate = quote == null ? null : quote.changeRate();

        boolean realtime = isRealtime(stock, quote);

        return new RankingResponse.Item(
                stock.getRankNo() == null ? 0 : stock.getRankNo(),
                stock.getSymbol(),
                stock.getName(),
                stock.getMarket(),
                stock.getStockCategory(),
                stock.getIsDividend(),
                number(stock.getLeverageFactor()),
                stock.getCurrency(),
                number(lastPrice),
                number(prevClose),
                number(changeAmount),
                number(changeRate),
                number(stock.getTradingAmount()),
                quote == null ? null : quote.getQuoteAt(),
                realtime
        );
    }

    private BigDecimal calculateChangeAmount(BigDecimal lastPrice, BigDecimal prevClose) {
        if (lastPrice == null || prevClose == null) return null;
        return lastPrice.subtract(prevClose);
    }

    private boolean isRealtime(Stock stock, QuoteSnapshot quote) {
        if (quote == null || quote.getQuoteAt() == null) return false;
        Instant now = Instant.now(clock);

        return marketSessionProvider.isOpen(
                stock.getMarketCountry(),
                now
        );
    }

    private MarketCountry parseMarket(String market) {
        if (market == null || market.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "market는 KR 또는 US여야 합니다");
        }
        String normalized = market.trim().toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "KR" -> MarketCountry.KR;
            case "US" -> MarketCountry.US;
            default -> throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "market는 KR 또는 US여야 합니다"
            );
        };
    }

    private void validateSize(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "size는 1 이상 100 이하만 가능합니다");
        }
    }

    private String encodeCursor(BigDecimal tradingAmount, Long stockId) {
        if (tradingAmount == null || stockId == null) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }

        String raw = tradingAmount.toPlainString() + ":" + stockId;

        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private RankingCursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_CURSOR_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }

        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            if (separator <= 0 || separator != raw.lastIndexOf(':') || separator == raw.length() - 1) {
                throw new BusinessException(ErrorCode.INVALID_CURSOR);
            }

            BigDecimal tradingAmount = new BigDecimal(raw.substring(0, separator));

            long stockId = Long.parseLong(raw.substring(separator + 1));

            if (tradingAmount.signum() < 0 || stockId < 1) throw new BusinessException(ErrorCode.INVALID_CURSOR);

            return new RankingCursor(tradingAmount, stockId);
        } catch (IllegalArgumentException exception) {
            /*
             * Base64 디코딩 오류와 숫자 형식 오류를
             * 외부 API용 BusinessException으로 변환한다.
             */
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }
    }

    private String number(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private record RankingCursor(
            BigDecimal tradingAmount,
            long stockId
    ) {
    }
}
