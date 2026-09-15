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
            String typed = stockRepository.hangulJamo(keyword, false);
            String composing = stockRepository.hangulJamo(keyword, true);

            return respond(stockRepository.searchByJamo(keyword),
                    stock -> jamoRank(stock, typed, composing), size);
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
                // rankNo 는 랭킹에서 빠지면 isRanked 와 같이 null 이 되므로(clearRanking),
                // nullsLast 하나로 "랭킹 종목 먼저, 그 안에서 1위부터"가 처리됩니다.
                // 시장별로 매기는 순위라 KR 1위와 US 1위는 동률이고, 그때는 이름순입니다.
                .thenComparing(
                        Stock::getRankNo,
                        Comparator.nullsLast(Integer::compareTo)
                )
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
     * <p>축이 둘입니다 — <b>타이핑한 글자가 완성형 그대로 맞았는지</b>가 먼저고,
     * 그 다음이 <b>앞에서 맞았는지</b>입니다. 조합 중인 마지막 글자('김처' → '김천')는
     * 완성될 글자를 추측한 것이라, 확실한 일치보다 아래에 둡니다.
     *
     * <pre>
     *   '김처' 검색 →  한김처머시기(완성형 부분)  김천에너지(조합중 접두)  가나김천(조합중 부분)
     * </pre>
     *
     * <p>검색어를 두 벌 받는 이유가 이것입니다. {@code typed} 는 컬럼과 같은 3칸 고정폭
     * ({@code partialTail = false}, 종성 없으면 {@code ^} 패딩)이라 글자 경계를 지키고,
     * {@code composing} 은 패딩이 없어 조합 중인 글자를 잡습니다. 마지막 글자에 종성이 있거나
     * 독립 자모('삼ㅅ')면 둘이 같은 값이라 조합중 단계는 자연히 비어 있습니다.
     */
    private int jamoRank(Stock stock, String typed, String composing) {
        String jamo = stock.getNameJamo();

        if (jamo == null) return 4;

        List<String> values = List.of(
                jamo,
                normalize(stock.getEnglishName()),
                normalize(stock.getSymbol())
        );

        if (values.stream().anyMatch(value -> value.equals(typed))) {
            return 0;
        }
        if (values.stream().anyMatch(value -> value.startsWith(typed))) {
            return 1;
        }
        if (values.stream().anyMatch(value -> value.contains(typed))) {
            return 2;
        }
        if (values.stream().anyMatch(value -> value.startsWith(composing))) {
            return 3;
        }

        return 4;
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
