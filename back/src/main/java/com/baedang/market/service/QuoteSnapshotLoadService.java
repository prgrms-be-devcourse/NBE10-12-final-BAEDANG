package com.baedang.market.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class QuoteSnapshotLoadService {

    private static final Logger log =
            LoggerFactory.getLogger(QuoteSnapshotLoadService.class);

    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final MarketDataPort marketDataPort;
    private final Clock clock;
    private final int universeSize;

    public QuoteSnapshotLoadService(
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            MarketDataPort marketDataPort,
            Clock clock,
            @Value("${trading.universe-size}") int universeSize
    ) {
        this.stockRepository = stockRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.marketDataPort = marketDataPort;
        this.clock = clock;
        this.universeSize = universeSize;
    }

    @Transactional
    public int syncQuotes(MarketCountry marketCountry) {
        List<Stock> rankedStocks =
                stockRepository.findRankedByMarketCountry(
                        marketCountry,
                        PageRequest.of(0, universeSize)
                );

        if (rankedStocks.isEmpty()) {
            log.debug(
                    "동기화할 유니버스 종목이 없습니다: marketCountry={}",
                    marketCountry
            );
            return 0;
        }

        Map<String, Stock> stockBySymbol = rankedStocks.stream()
                .collect(Collectors.toMap(
                        Stock::getSymbol,
                        Function.identity(),
                        (left, right) -> left
                ));

        List<String> symbols = rankedStocks.stream()
                .map(Stock::getSymbol)
                .toList();

        List<PriceQuote> quotes = marketDataPort.fetchPrices(symbols);
        if (quotes.isEmpty()) {
            log.warn(
                    "외부 시세 응답이 비어 있습니다: marketCountry={}, symbolCount={}",
                    marketCountry,
                    symbols.size()
            );
            return 0;
        }
        OffsetDateTime collectedAt = clock.instant().atOffset(ZoneOffset.UTC);

        List<Long> stockIds = rankedStocks.stream()
                .map(Stock::getStockId)
                .toList();

        Map<Long, QuoteSnapshot> existingSnapshots =
                quoteSnapshotRepository.findByStockIdIn(stockIds).stream()
                        .collect(Collectors.toMap(
                                QuoteSnapshot::getStockId,
                                Function.identity()
                        ));

        int updatedCount = 0;

        for (PriceQuote quote : quotes) {
            Stock stock = stockBySymbol.get(quote.symbol());
            if (stock == null) {
                log.warn(
                        "요청하지 않은 종목의 현재가 응답을 건너뜁니다: symbol={}",
                        quote.symbol()
                );
                continue;
            }

            if (!isValidQuote(stock, quote)) {
                continue;
            }

            String currency = quote.currency()
                    .trim()
                    .toUpperCase(Locale.ROOT);

            QuoteSnapshot snapshot =
                    existingSnapshots.get(stock.getStockId());

            if (snapshot != null) {
                snapshot.updatePrice(
                        quote.lastPrice(),
                        currency,
                        quote.quoteAt(),
                        collectedAt
                );
            } else {
                QuoteSnapshot newSnapshot = new QuoteSnapshot(
                        stock.getStockId(),
                        quote.lastPrice(),
                        currency,
                        quote.quoteAt(),
                        collectedAt
                );
                quoteSnapshotRepository.save(newSnapshot);
            }

            updatedCount++;
        }

        log.info(
                "시세 스냅샷 동기화 완료: marketCountry={}, updatedCount={}/{}",
                marketCountry,
                updatedCount,
                rankedStocks.size()
        );

        return updatedCount;
    }

    private boolean isValidQuote(Stock stock, PriceQuote quote) {
        if (quote.lastPrice() == null || quote.quoteAt() == null) {
            log.debug(
                    "가격 또는 체결 시각이 없는 현재가 응답을 건너뜁니다: symbol={}",
                    quote.symbol()
            );
            return false;
        }

        String stockCurrency = stock.getCurrency();
        String quoteCurrency = quote.currency();

        if (stockCurrency == null
                || stockCurrency.isBlank()
                || quoteCurrency == null
                || quoteCurrency.isBlank()
                || !stockCurrency.trim().equalsIgnoreCase(quoteCurrency.trim())) {
            log.warn(
                    "현재가 통화 불일치로 저장을 건너뜁니다: symbol={}, stockCurrency={}, quoteCurrency={}",
                    quote.symbol(),
                    stockCurrency,
                    quoteCurrency
            );
            return false;
        }

        return true;
    }
}
