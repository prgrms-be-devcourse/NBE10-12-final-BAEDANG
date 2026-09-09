package com.baedang.market.service;

import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.QuoteSnapshotBatchRepository;
import com.baedang.stock.entity.Stock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale;

@Service
public class QuoteSnapshotPersistenceService {
    private static final BigDecimal PRICE_LIMIT = new BigDecimal("1000000000000000");
    private final QuoteSnapshotBatchRepository repository;

    public QuoteSnapshotPersistenceService(QuoteSnapshotBatchRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int saveOrUpdate(List<Stock> stocks, List<PriceQuote> quotes, OffsetDateTime collectedAt) {
        Map<String, Stock> bySymbol = stocks.stream().collect(Collectors.toMap(
                stock -> DomainNormalizer.symbol(stock.getSymbol()), Function.identity()));
        Map<Long, QuoteSnapshot> snapshots = new HashMap<>();
        for (PriceQuote quote : quotes) {
            if (quote == null) continue;
            Stock stock = bySymbol.get(DomainNormalizer.symbol(quote.symbol()));
            if (stock == null || quote.lastPrice() == null || quote.lastPrice().signum() <= 0
                    || quote.lastPrice().compareTo(PRICE_LIMIT) >= 0
                    || !isRepresentableAtScale(quote.lastPrice(), 4)
                    || quote.quoteAt() == null || quote.quoteAt().isAfter(collectedAt)
                    || stock.getCurrency() == null || stock.getCurrency().isBlank()
                    || !DomainNormalizer.currency(stock.getCurrency()).equals(DomainNormalizer.currency(quote.currency()))) {
                continue;
            }
            QuoteSnapshot candidate = new QuoteSnapshot(stock.getStockId(), quote.lastPrice(),
                    DomainNormalizer.currency(quote.currency()), quote.quoteAt(), collectedAt);
            snapshots.merge(stock.getStockId(), candidate,
                    (left, right) -> left.getQuoteAt().isAfter(right.getQuoteAt()) ? left : right);
        }
        // 겹친 배치도 동일한 순서로 저장하여 행 잠금의 순환 대기를 피합니다.
        List<QuoteSnapshot> ordered = snapshots.values().stream()
                .sorted(Comparator.comparing(QuoteSnapshot::getStockId)).toList();
        return repository.savePrices(ordered);
    }

    /** 현재가와 별도 컬럼만 갱신하여 detached 엔티티 merge가 최신 현재가를 덮지 않게 합니다. */
    @Transactional
    public void updatePrevClose(Long stockId, BigDecimal price) {
        repository.updatePrevClose(stockId, price);
    }
}
