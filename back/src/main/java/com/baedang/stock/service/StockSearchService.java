package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.stock.dto.StockSearchResponse;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class StockSearchService {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 100;

    private static final char JAEUM_FIRST = 0x3131;
    private static final char JAEUM_LAST = 0x314E;
    private static final char MOEUM_FIRST = 0x314F;
    private static final char MOEUM_LAST = 0x3163;

    // LIKE 와일드카드로 해석되지 않도록 검색어를 정규화합니다.
    private static final Pattern LIKE_METACHARS = Pattern.compile("[%_\\\\]");

    private StockRepository stockRepository;

    public StockSearchService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public StockSearchResponse search(String query) {
        return search(query, DEFAULT_SIZE);
    }

    public StockSearchResponse search(String query, int size) {
        String keyword = normalizeQuery(query);
        validateSize(size);

        if (isChosungOnly(keyword)) {
            return respond(stockRepository.searchByChosung(keyword),
                    stock -> chosungRank(stock, keyword), size);
        }

        if (isValid(keyword)) {
            return respond(stockRepository.searchByJamo(keyword),
                    stock -> matchRank(stock, keyword), size);
        }

        return new StockSearchResponse(List.of());
    }

    private StockSearchResponse respond(List<Stock> found, ToIntFunction<Stock> rank, int size) {
        List<StockSearchResponse.Item> items = found.stream().sorted(searchOrder(rank)).limit(size).map(this::toItem).toList();

        return new StockSearchResponse(items);
    }

    private boolean isValid(String keyword) {
        int last = keyword.length() - 1;

        for (int i = 0; i <= last; i++) {
            char c = keyword.charAt(i);

            if (JAEUM_FIRST <= c && c <= MOEUM_LAST) {
                if (i == last && (JAEUM_FIRST <= c && c <= JAEUM_LAST)) continue;
                return false;
            }
        }

        return true;
    }

    private boolean isChosungOnly(String keyword) {
        return keyword.chars().allMatch(c -> JAEUM_FIRST <= c && c <= JAEUM_LAST);
    }

    private Comparator<Stock> searchOrder(ToIntFunction<Stock> rank) {
        return Comparator
                .<Stock>comparingInt(rank)
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

        String prefix = withoutTrailingJaeum(keyword);

        if (values.stream().anyMatch(value -> value.startsWith(prefix))) {
            return 1;
        }

        return 2;
    }

    /**
     * 초성 검색 결과의 순위. 검색이 {@code name_chosung} 으로 걸린 것이므로
     * 순위도 같은 초성 공간에서 매겨야 합니다 — 원문 이름과 'ㅅㅅㅈㅈ' 를 비교하면
     * 모든 결과가 같은 순위가 되어 정렬이 이름순으로만 남습니다.
     */
    private int chosungRank(Stock stock, String keyword) {
        String chosung = stock.getNameChosung();

        if (chosung == null) return 2;
        if (chosung.equals(keyword)) return 0;
        if (chosung.startsWith(keyword)) return 1;

        return 2;
    }

    /**
     * 미완성 입력('삼ㅅ')의 접두 판정용. 마지막 독립 자음을 떼고 완성된 부분만 봅니다
     * (원문 이름에는 독립 자음이 없어 그대로 비교하면 접두 일치가 영영 안 잡힙니다).
     */
    private String withoutTrailingJaeum(String keyword) {
        char last = keyword.charAt(keyword.length() - 1);

        if (JAEUM_FIRST <= last && last <= JAEUM_LAST && keyword.length() > 1) {
            return keyword.substring(0, keyword.length() - 1);
        }

        return keyword;
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.INVALID_QUERY);
        }

        String normalized = LIKE_METACHARS.matcher(normalize(query)).replaceAll("");

        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_QUERY);
        }

        return normalized;
    }

    private String normalize(String value) {
        if (value == null) return "";

        return DomainNormalizer.searchKey(value);
    }

    private void validateSize(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "size는 1 이상 100 이하만 가능합니다");
        }
    }

    private StockSearchResponse.Item toItem(Stock stock) {
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
