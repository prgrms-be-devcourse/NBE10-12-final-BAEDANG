package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.dto.StockLikePageResponse;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockLike;
import com.baedang.stock.repository.StockLikeRepository;
import com.baedang.stock.repository.StockRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.transaction.annotation.Propagation.NEVER;

@Service
@Transactional(readOnly = true)
public class StockLikeService {
    private final StockLikeRepository stockLikeRepository;
    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final StockOnDemandQuoteService stockOnDemandQuoteService;

    public StockLikeService(StockLikeRepository stockLikeRepository,
                            StockRepository stockRepository,
                            QuoteSnapshotRepository quoteSnapshotRepository,
                            StockOnDemandQuoteService stockOnDemandQuoteService) {
        this.stockLikeRepository = stockLikeRepository;
        this.stockRepository = stockRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.stockOnDemandQuoteService = stockOnDemandQuoteService;
    }

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    @Transactional
    public void like(Long userId, Long stockId) {
        if (!stockRepository.existsById(stockId)) {
            throw new BusinessException(ErrorCode.STOCK_NOT_FOUND, "stockId=" + stockId);
        }
        stockLikeRepository.insertIfAbsent(userId, stockId);
    }

    @Transactional
    public void unlike(Long userId, Long id) {
        stockLikeRepository.deleteByUserIdAndStockLikeId(userId, id);
    }

    @Transactional(propagation = NEVER)
    public StockLikePageResponse getLikes(Long userId, String cursor, Integer size) {
        int pageSize = (size == null || size <= 0) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);

        // 다음 페이지가 있는지도 같이 확인하려고 다음 한 행(`pageSize + 1`)을 더 가져온다.
        List<StockLike> rows = stockLikeRepository.findPage(
                userId, decodeCursor(cursor), PageRequest.of(0, pageSize + 1));
        boolean hasNext = rows.size() > pageSize;
        List<StockLike> stockLikes = hasNext ? rows.subList(0, pageSize) : rows;

        String nextCursor = stockLikes.isEmpty()
                ? null
                : encodeCursor(stockLikes.get(stockLikes.size() - 1).getStockLikeId());

        return new StockLikePageResponse(stockLikesToItems(stockLikes), nextCursor, hasNext);
    }

    private List<StockLikePageResponse.Item> stockLikesToItems(List<StockLike> stockLikes) {
        List<Long> stockIds = stockLikes.stream().map(StockLike::getStockId).toList();

        Map<Long, Stock> stockIdStockMap = stockRepository
                .findByStockIdIn(stockIds)
                .stream()
                .collect(Collectors.toMap(Stock::getStockId, Function.identity()));

        Map<Long, QuoteSnapshot> stockIdQuoteMap = quoteSnapshotRepository
                .findByStockIdIn(stockIds)
                .stream()
                .collect(Collectors.toMap(QuoteSnapshot::getStockId, Function.identity()));

        return stockLikes
                .stream()
                .map(stockLike -> {
                    // stock_like.stock_id는 FK(ON DELETE CASCADE)라 종목이 항상 조회된다.
                    Stock stock = stockIdStockMap.get(stockLike.getStockId());

                    // 시세가 한 번도 수집되지 않은 종목만 온디맨드로 채운다(종목당 최초 1회).
                    // Toss 실패 시 예외 없이 null 이 돌아오고, 가격 필드가 null 로 내려간다.
                    QuoteSnapshot quote = stockIdQuoteMap.get(stockLike.getStockId());
                    if (quote == null) {
                        quote = stockOnDemandQuoteService.ensureQuote(stock, null);
                    }

                    return StockLikePageResponse.Item.of(stockLike, stock, quote);
                })
                .toList();
    }

    private static String encodeCursor(Long stockLikeId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(stockLikeId.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) { // Base64 오류와 NumberFormatException 모두
            throw new BusinessException(ErrorCode.INVALID_CURSOR, "커서 디코딩 실패: " + cursor);
        }
    }
}
