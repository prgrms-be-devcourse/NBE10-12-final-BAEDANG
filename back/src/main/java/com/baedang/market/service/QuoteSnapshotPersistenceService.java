package com.baedang.market.service;

import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.market.entity.DailyCandle;
import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.port.MarketCalendarDay;
import com.baedang.market.port.PriceQuote;
import com.baedang.market.repository.QuoteSnapshotBatchRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.baedang.trading.support.DecimalScaleValidator.isRepresentableAtScale;

@Service
public class QuoteSnapshotPersistenceService {
    private static final Logger log = LoggerFactory.getLogger(QuoteSnapshotPersistenceService.class);
    private static final BigDecimal PRICE_LIMIT = new BigDecimal("1000000000000000");
    private final QuoteSnapshotBatchRepository repository;
    private final MarketTradingDayPolicy tradingDays;

    public QuoteSnapshotPersistenceService(QuoteSnapshotBatchRepository repository, MarketTradingDayPolicy tradingDays) {
        this.repository = repository;
        this.tradingDays = tradingDays;
    }

    @Transactional(propagation = Propagation.NEVER)
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
            try {
                Optional<LocalDate> tradeDate = tradingDays.quoteTradeDate(stock.getMarketCountry(), quote.quoteAt().toInstant());
                if (tradeDate.isEmpty()) continue;
                // 현재가 저장 시 기존 일봉 행만으로 기준가가 검증되었다고 판단하지 않는다.
            } catch (RuntimeException unavailableSession) {
                log.warn("Session validation failed: stockId={} type={}", stock.getStockId(), unavailableSession.getClass().getSimpleName());
                continue;
            }
            snapshots.merge(stock.getStockId(), candidate,
                    (left, right) -> left.getQuoteAt().isAfter(right.getQuoteAt()) ? left : right);
        }
        // 겹친 배치도 동일한 순서로 저장하여 행 잠금의 순환 대기를 피합니다.
        List<QuoteSnapshot> ordered = snapshots.values().stream()
                .sorted(Comparator.comparing(QuoteSnapshot::getStockId)).toList();
        int updated = 0;
        Map<Long, MarketCountry> countries = stocks.stream().collect(Collectors.toMap(Stock::getStockId, Stock::getMarketCountry));
        for (MarketCountry country : MarketCountry.values()) {
            List<QuoteSnapshot> group = ordered.stream().filter(q -> countries.get(q.getStockId()) == country).toList();
            if (!group.isEmpty()) updated += repository.savePrices(group, country);
        }
        return updated;
    }

    /** 이번 복구의 외부 조회에서 검증해 반환한 기준가 일봉만 전달한다. */
    @Transactional(propagation = Propagation.NEVER)
    public int repairReference(Stock stock, QuoteSnapshot expected, DailyCandle reference) {
        if (reference == null) return 0;
        Optional<LocalDate> tradeDate = tradingDays.quoteTradeDate(stock.getMarketCountry(), expected.getQuoteAt().toInstant());
        if (tradeDate.isEmpty() || !tradingDays.previousTradingDay(stock.getMarketCountry(), tradeDate.get())
                .filter(reference.getTradeDate()::equals).isPresent()) return 0;
        return repository.repairReference(expected, stock.getMarketCountry(), reference.getTradeDate(), reference.getClosePrice());
    }

    /** 기존 DB 조회 결과 대신 이번 복구의 외부 조회에서 검증해 반환한 일봉만 전달한다. */
    @Transactional(propagation = Propagation.NEVER)
    public int saveRecoveredClose(Stock stock, QuoteSnapshot expected, DailyCandle close, DailyCandle reference,
            OffsetDateTime collectedAt) {
        MarketCalendarDay day = tradingDays.calendar(stock.getMarketCountry(), close.getTradeDate());
        if (!day.isOpen() || day.regularCloseAt() == null) return 0;
        QuoteSnapshot candidate = new QuoteSnapshot(stock.getStockId(), close.getClosePrice(), stock.getCurrency(),
                day.regularCloseAt(), collectedAt);
        if (reference != null && tradingDays.previousTradingDay(stock.getMarketCountry(), close.getTradeDate())
                .filter(reference.getTradeDate()::equals).isPresent()) {
            candidate.applyReference(reference.getTradeDate(), reference.getClosePrice());
        }
        return repository.saveRecoveredClose(candidate, expected);
    }

    public int clearMismatchedReference(QuoteSnapshot expected, LocalDate requiredDate) {
        return repository.clearMismatchedReference(expected, requiredDate);
    }

}
