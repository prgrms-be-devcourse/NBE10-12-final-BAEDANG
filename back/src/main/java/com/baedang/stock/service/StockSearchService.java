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
            // 자모 분해는 검색어당 한 번만. 비교자 안에서 부르면 정렬 비교마다 DB를 왕복합니다.
            String jamoKeyword = stockRepository.hangulJamo(keyword, false);

            return respond(stockRepository.searchByJamo(keyword),
                    stock -> jamoRank(stock, jamoKeyword), size);
        }

        return new StockSearchResponse(List.of());
    }

    private boolean isChosungOnly(String keyword) {
        return keyword.chars().allMatch(c -> JAEUM_FIRST <= c && c <= JAEUM_LAST);
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

    private StockSearchResponse respond(List<Stock> found, ToIntFunction<Stock> getRank, int size) {
        List<StockSearchResponse.Item> items = found
                .stream()
                .sorted(createComparator(getRank))
                .limit(size)
                .map(this::toItem)
                .toList();

        return new StockSearchResponse(items);
    }

    private Comparator<Stock> createComparator(ToIntFunction<Stock> getRank) {
        return Comparator
                .comparingInt(getRank)
                .thenComparing(
                        Stock::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                )
                .thenComparing(
                        Stock::getStockId,
                        Comparator.nullsLast(Long::compareTo)
                );
    }

    // 초성 검색의 검색어와 검색 결과를 비교해서 순위를 반환합니다.
    private int chosungRank(Stock stock, String keyword) {
        String chosung = stock.getNameChosung();

        if (chosung == null) return 2;
        if (chosung.equals(keyword)) return 0;
        if (chosung.startsWith(keyword)) return 1;

        return 2;
    }

    /**
     * 자모 검색의 검색어와 검색 결과를 비교해서 순위를 반환합니다.
     *
     * <p>검색어는 컬럼과 같은 3칸 고정폭({@code partialTail = false})으로 분해한 것을 씁니다.
     * 검색(LIKE)은 조합 중인 음절을 살리려고 {@code true} 로 관대하게 걸지만, 순위는 그 패딩이
     * 있어야 글자 경계를 지킵니다 — {@code true}('서' → ㅅㅓ)로 접두를 재면 '성우하이텍'(ㅅㅓㅇ…)이
     * '서' 의 접두로 잡혀 진짜 접두인 '서울가스' 와 동률이 됩니다.
     */
    private int jamoRank(Stock stock, String keyword) {
        String jamo = stock.getNameJamo();

        if (jamo == null) return 2;

        List<String> values = List.of(
                jamo,
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
