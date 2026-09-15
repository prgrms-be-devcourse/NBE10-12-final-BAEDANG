package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockInfo;
import com.baedang.stock.port.SymbolInfoPort;
import com.baedang.stock.repository.StockRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 주문과 호가 공급이 공유하는 상태 조회. TTL은 수신 캐시 정책이지 거래소 상태의 실시간 보장이 아닙니다. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class StockTradingStatusService {
    private static final int MAX_CACHE_SIZE = 1000;
    private final SymbolInfoPort port;
    private final StockTradingStatusPersistenceService persistence;
    private final StockRepository stocks;
    private final Clock clock;
    private final Duration ttl;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final Map<Long, Instant> refreshedAt = new LinkedHashMap<>();

    public StockTradingStatusService(SymbolInfoPort port, StockTradingStatusPersistenceService persistence,
            StockRepository stocks, Clock clock, @Value("${trading.stock-status-cache-ttl:5m}") Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("상태 캐시 TTL은 양수여야 합니다");
        this.port = port;
        this.persistence = persistence;
        this.stocks = stocks;
        this.clock = clock;
        this.ttl = ttl;
    }

    public Stock requireCurrent(Stock stock) {
        List<Stock> result = refreshBatch(List.of(stock));
        if (result.isEmpty()) throw new BusinessException(ErrorCode.STOCK_STATUS_UNAVAILABLE);
        return result.getFirst();
    }

    /** 최대 200개 상태를 한 호출로 확보합니다. 누락/잘못된 상태는 결과에서 제외하며 성공 캐시하지 않습니다. */
    public List<Stock> refreshBatch(List<Stock> targets) {
        if (targets.isEmpty()) return List.of();
        if (targets.size() > 200) throw new IllegalArgumentException("상태 조회는 최대 200종목입니다");
        boolean acquired = false;
        try {
            acquired = refreshLock.tryLock(5, TimeUnit.SECONDS);
            if (!acquired) throw new BusinessException(ErrorCode.STOCK_STATUS_UNAVAILABLE);
            Instant now = clock.instant();
            refreshedAt.entrySet().removeIf(e -> e.getValue().isAfter(now) || !e.getValue().plus(ttl).isAfter(now));
            List<Stock> missing = targets.stream().filter(s -> !refreshedAt.containsKey(s.getStockId())).toList();
            if (!missing.isEmpty()) {
                Instant requestedAt = clock.instant();
                List<StockInfo> response = port.fetchStocks(missing.stream().map(Stock::getSymbol).toList());
                Map<StockKey, List<StockInfo>> indexed = indexResponse(response);
                for (Stock stock : missing) {
                    List<StockInfo> matches = indexed.getOrDefault(
                            new StockKey(stock.getSymbol(), stock.getMarket(), stock.getCurrency()), List.of());
                    if (matches.size() != 1) continue;
                    StockInfo info = matches.getFirst();
                    if (!Set.of("ACTIVE", "DELISTED").contains(info.status() == null ? "" : info.status())
                            || (stock.getMarketCountry() == MarketCountry.KR && info.krMarketDetail() == null)) continue;
                    persistence.update(stock.getStockId(), stock.getMarketCountry(), info);
                    if (!requestedAt.plus(ttl).isAfter(clock.instant())) continue;
                    refreshedAt.put(stock.getStockId(), requestedAt);
                    while (refreshedAt.size() > MAX_CACHE_SIZE) refreshedAt.remove(refreshedAt.keySet().iterator().next());
                }
            }
            List<Long> valid = new ArrayList<>();
            for (Stock stock : targets) {
                Instant at = refreshedAt.get(stock.getStockId());
                if (at != null && at.plus(ttl).isAfter(clock.instant())) valid.add(stock.getStockId());
            }
            return stocks.findByStockIdIn(valid);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.STOCK_STATUS_UNAVAILABLE);
        } finally {
            if (acquired) refreshLock.unlock();
        }
    }

    /** 중복 응답을 임의 선택하지 않도록 같은 키의 응답을 모두 보존합니다. */
    private Map<StockKey, List<StockInfo>> indexResponse(List<StockInfo> response) {
        Map<StockKey, List<StockInfo>> indexed = new HashMap<>();
        if (response == null) return indexed;
        for (StockInfo info : response) {
            if (info == null || info.symbol() == null || info.market() == null || info.currency() == null) continue;
            StockKey key = new StockKey(DomainNormalizer.symbol(info.symbol()), info.market(), info.currency());
            indexed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(info);
        }
        return indexed;
    }

    private record StockKey(String symbol, String market, String currency) {}
}
