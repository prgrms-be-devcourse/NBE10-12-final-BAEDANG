package com.baedang.stock.service;

import com.baedang.market.entity.QuoteSnapshot;
import com.baedang.market.repository.QuoteSnapshotRepository;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.RankingEntry;
import com.baedang.stock.port.RankingPort;
import com.baedang.stock.port.RankingSnapshot;
import com.baedang.stock.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StockRankingLoadService {

    private static final Logger log = LoggerFactory.getLogger(StockRankingLoadService.class);

    private final RankingPort rankingPort;
    private final StockRepository stockRepository;
    private final QuoteSnapshotRepository quoteSnapshotRepository;
    private final Clock clock;
    private final StockRankingLoadService self;

    public StockRankingLoadService(
            RankingPort rankingPort,
            StockRepository stockRepository,
            QuoteSnapshotRepository quoteSnapshotRepository,
            Clock clock,
            @Lazy StockRankingLoadService self
    ) {
        this.rankingPort = rankingPort;
        this.stockRepository = stockRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.clock = clock;
        this.self = self;
    }

    // 두 시장의 랭킹 적재 스케줄 시간이 다르므로 이 메서드는 테스트 용도로 사용한다.
    public void loadAll() {
        for (MarketCountry marketCountry : MarketCountry.values()) {
            load(marketCountry);
        }
    }

    public void load(MarketCountry marketCountry) {
        RankingSnapshot rankingSnapshot = rankingPort.fetchRanking(marketCountry);

        // 보통 휴장일에 빈 배열이 온다. 예외가 있을 수 있음.
        if (rankingSnapshot.entries().isEmpty()) {
            log.warn(
                    "StockRankingLoadService(marketCountry={}): 랭킹 집계 결과가 비어 있어 직전 유니버스를 유지합니다.",
                    marketCountry
            );
        } else {
            self.applyRanking(marketCountry, rankingSnapshot.entries(), rankingSnapshot.rankedAt());
        }

    }

    @Transactional
    public void applyRanking(
            MarketCountry marketCountry,
            List<RankingEntry> entries,
            OffsetDateTime rankedAt
    ) {
        OffsetDateTime collectedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime quoteAt = rankedAt == null ? collectedAt : rankedAt;

        Set<Long> previouslyRanked = clearRanking(marketCountry);
        Map<Long, RankingEntry> targets =
                overwriteRanking(marketCountry, entries, previouslyRanked);
        syncQuotes(marketCountry, targets, quoteAt, collectedAt);
    }

    /** @return 직전 유니버스의 stock_id 집합. 이번 주 신규 편입 판별에 쓴다. */
    private Set<Long> clearRanking(MarketCountry marketCountry) {
        List<Stock> previous = stockRepository.findByMarketCountryAndIsRankedTrue(marketCountry);
        previous.forEach(Stock::clearRanking);

        return previous.stream()
                .map(Stock::getStockId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** @return 신규 편입 종목의 {@code stock_id → 랭킹 엔트리}. 시세 초기화 대상이다. */
    private Map<Long, RankingEntry> overwriteRanking(
            MarketCountry marketCountry,
            List<RankingEntry> entries,
            Set<Long> previouslyRanked
    ) {
        Map<String, Stock> stocksSymbolMap = stockRepository
                .findByMarketCountryAndSymbolIn(
                        marketCountry,
                        entries.stream().map(entry -> normalizeSymbol(entry.symbol())).toList()
                )
                .stream()
                .collect(Collectors.toMap(
                        Stock::getSymbol,
                        stock -> stock,
                        (left, right) -> left
                ));

        List<String> unknownSymbols = new ArrayList<>();
        Map<Long, RankingEntry> targets = new LinkedHashMap<>();

        for (RankingEntry entry : entries) {
            String symbol = normalizeSymbol(entry.symbol());
            Stock stock = stocksSymbolMap.get(symbol);

            if (stock == null) {
                unknownSymbols.add(symbol);
                log.warn(
                        "StockRankingLoadService(marketCountry={}): stock 테이블에 없는 심볼, 랭킹 적재 생략 (symbol={}, rank={})",
                        marketCountry,
                        symbol,
                        entry.rank()
                );
                continue;
            }

            stock.applyRanking(entry.rank(), entry.tradingAmount());

            if (isNewlyRanked(stock, previouslyRanked) && hasMatchingCurrency(stock, entry)) {
                targets.put(stock.getStockId(), entry);
            }
        }

        if (!unknownSymbols.isEmpty()) {
            log.warn(
                    "StockRankingLoadService(marketCountry={}): 랭킹 {}건 중 {}건이 stock 테이블에 없어 생략됨. (symbols={})",
                    marketCountry,
                    entries.size(),
                    unknownSymbols.size(),
                    unknownSymbols
            );
        }

        return targets;
    }

    private void syncQuotes(
            MarketCountry marketCountry,
            Map<Long, RankingEntry> targets,
            OffsetDateTime quoteAt,
            OffsetDateTime collectedAt
    ) {
        if (targets.isEmpty()) return;

        Map<Long, QuoteSnapshot> qouteSnapshotStockIdMap = quoteSnapshotRepository
                .findByStockIdIn(targets.keySet())
                .stream()
                .collect(Collectors.toMap(QuoteSnapshot::getStockId, Function.identity()));

        int createdCount = 0;
        int prevCloseCount = 0;
        int skippedCount = 0;

        for (Map.Entry<Long, RankingEntry> target : targets.entrySet()) {
            Long stockId = target.getKey();
            RankingEntry entry = target.getValue();
            QuoteSnapshot quoteSnapshot = qouteSnapshotStockIdMap.get(stockId);

            if (quoteSnapshot == null) {
                if (!canCreate(entry)) {
                    skippedCount++;
                    continue;
                }

                quoteSnapshot = quoteSnapshotRepository.save(new QuoteSnapshot(
                        stockId,
                        entry.lastPrice(),
                        normalizeCurrency(entry.currency()),
                        quoteAt,
                        collectedAt
                ));
                createdCount++;
            }

            if (isUsablePrevClose(entry.basePrice())) {
                quoteSnapshot.updatePrevClose(entry.basePrice());
                prevCloseCount++;
            } else {
                // 0% 로 속이지 않는다 — prev_close 를 비워두면 등락률이 null 로 나간다.
                log.warn(
                        "StockRankingLoadService: 기준가가 유효하지 않아 prev_close 를 세팅하지 않습니다. "
                                + "(symbol={}, basePrice={})",
                        entry.symbol(),
                        entry.basePrice()
                );
                skippedCount++;
            }
        }

        log.info(
                "StockRankingLoadService(marketCountry={}): 신규 편입 {}건 시세 초기화 "
                        + "(스냅샷 생성={}, prev_close 세팅={}, 건너뜀={})",
                marketCountry,
                targets.size(),
                createdCount,
                prevCloseCount,
                skippedCount
        );
    }

    private boolean isNewlyRanked(Stock stock, Set<Long> previouslyRanked) {
        return stock.getStockId() != null && !previouslyRanked.contains(stock.getStockId());
    }

    private boolean hasMatchingCurrency(Stock stock, RankingEntry entry) {
        String stockCurrency = stock.getCurrency();
        String entryCurrency = entry.currency();

        if (stockCurrency == null
                || stockCurrency.isBlank()
                || entryCurrency == null
                || entryCurrency.isBlank()
                || !stockCurrency.trim().equalsIgnoreCase(entryCurrency.trim())) {
            log.warn(
                    "StockRankingLoadService: 랭킹 통화 불일치로 시세 초기화를 건너뜁니다. "
                            + "(symbol={}, stockCurrency={}, rankingCurrency={})",
                    stock.getSymbol(),
                    stockCurrency,
                    entryCurrency
            );
            return false;
        }
        return true;
    }

    private boolean canCreate(RankingEntry entry) {
        if (entry.lastPrice() == null
                || entry.lastPrice().signum() <= 0
                || entry.currency() == null
                || entry.currency().isBlank()) {
            log.warn(
                    "StockRankingLoadService: 현재가·통화가 없어 스냅샷 생성을 건너뜁니다. "
                            + "(symbol={}, lastPrice={}, currency={})",
                    entry.symbol(),
                    entry.lastPrice(),
                    entry.currency()
            );
            return false;
        }
        return true;
    }

    private boolean isUsablePrevClose(BigDecimal basePrice) {
        return basePrice != null && basePrice.signum() > 0;
    }

    private String normalizeCurrency(String currency) {
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
