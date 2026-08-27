package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.dto.StockSearchResponse;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class StockSearchService {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 100;

    private StockRepository stockRepository;

    public StockSearchService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public StockSearchResponse search(String query, int size) {
        String keyword = normalizeQuery(query);
        validateSize(size);

        List<StockSearchResponse.Item> items = stockRepository.searchByKeyword(keyword).stream().sorted(searchOrder(keyword)).limit(size).map(this::toItem).toList();

        return new StockSearchResponse(items);
    }

    public StockSearchResponse search(String query) {
        return search(query, DEFAULT_SIZE);
    }

    private Comparator<Stock> searchOrder(String keyword) {
        return Comparator
                .<Stock>comparingInt(stock -> matchRank(stock, keyword))
                .thenComparing(
                        Stock::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                )
                .thenComparing(
                        Stock::getStockId,
                        Comparator.nullsLast(Long::compareTo)
                );
    }

    private int matchRank(Stock stock, String keyword) {
        List<String> values = List.of(
                normalize(stock.getName()),
                normalize(stock.getEnglishName()),
                normalize(stock.getSymbol())
        );

        if (values.stream().anyMatch(value -> value.equals(keyword))) {
            return 0;
        }

        if (values.stream().anyMatch(value -> value.startsWith(keyword))) {
            return 1;
        }

        return 2;
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.INVALID_QUERY);
        }

        String normalized = normalize(query);

        if (normalized.length() < 2) {
            throw new BusinessException(ErrorCode.INVALID_QUERY);
        }

        return normalized;
    }

    private String normalize(String value) {
        if (value == null) return "";

        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private void validateSize(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "size는 1 이상 100 이하만 가능합니다");
        }
    }

    private StockSearchResponse.Item toItem(Stock stock){
        return new StockSearchResponse.Item(
                stock.getSymbol(),
                stock.getName(),
                stock.getEnglishName(),
                stock.getMarket(),
                stock.getMarketCountry(),
                stock.getStockCategory()
        );
    }
}
