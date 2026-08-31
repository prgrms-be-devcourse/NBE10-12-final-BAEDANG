package com.baedang.market.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class QuoteSnapshotPersistenceService {

    private static final Logger log =
            LoggerFactory.getLogger(QuoteSnapshotPersistenceService.class);

    private final QuoteSnapshotRepository quoteSnapshotRepository;

    public QuoteSnapshotPersistenceService(QuoteSnapshotRepository quoteSnapshotRepository) {
        this.quoteSnapshotRepository = quoteSnapshotRepository;
    }

    @Transactional
    public int saveOrUpdate(
            List<Stock> stocks,
            List<PriceQuote> quotes,
            OffsetDateTime collectedAt
    ) {
        Map<String, Stock> stockBySymbol = stocks.stream()
                .collect(Collectors.toMap(
                        stock -> normalizeSymbol(stock.getSymbol()),
                        Function.identity(),
                        (left, right) -> left
                ));

        List<Long> stockIds = stocks.stream()
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
            Stock stock = stockBySymbol.get(normalizeSymbol(quote.symbol()));
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
            QuoteSnapshot snapshot = existingSnapshots.get(stock.getStockId());

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
        return updatedCount;
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null
                ? null
                : symbol.trim().toUpperCase(Locale.ROOT);
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
