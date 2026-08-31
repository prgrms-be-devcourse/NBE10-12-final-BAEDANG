package com.baedang.stock.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.RankingEntry;
import com.baedang.stock.port.RankingPort;
import com.baedang.stock.port.RankingSnapshot;
import com.baedang.stock.repository.StockRepository;
import com.baedang.standard.utils.Pacer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StockRankingLoadService {
    private final RankingPort rankingPort;
    private final StockRepository stockRepository;
    private final StockRankingLoadService self;

    public StockRankingLoadService(
            RankingPort rankingPort,
            StockRepository stockRepository,
            @Lazy StockRankingLoadService self
    ) {
        this.rankingPort = rankingPort;
        this.stockRepository = stockRepository;
        this.self = self;
    }

    private static final int TPS = 5;

    private static final Logger logger = LoggerFactory.getLogger(StockRankingLoadService.class);

    public void loadAll() {
        Pacer pacer = Pacer.forTps(TPS);

        for (MarketCountry marketCountry : MarketCountry.values()) {
            RankingSnapshot snapshot = rankingPort.fetchRanking(marketCountry);

            boolean isHoliday = snapshot.entries().isEmpty();
            if (!isHoliday) self.applyRanking(marketCountry, snapshot.entries());

            pacer.pace();
        }
    }

    @Transactional
    public void applyRanking(MarketCountry marketCountry, List<RankingEntry> entries) {
        clearRanking(marketCountry);
        overwriteRanking(marketCountry, entries);
    }

    private void clearRanking(MarketCountry marketCountry) {
        stockRepository
                .findByMarketCountryAndIsRankedTrue(marketCountry)
                .forEach(Stock::clearRanking);
    }

    private void overwriteRanking(MarketCountry marketCountry, List<RankingEntry> entries) {
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

        for (RankingEntry entry : entries) {
            String symbol = normalizeSymbol(entry.symbol());
            Stock stock = stocksSymbolMap.get(symbol);

            if (stock == null) {
                unknownSymbols.add(symbol);
                logger.warn(
                        "StockRankingLoadService(marketCountry={}): stock 테이블에 없는 심볼, 랭킹 적재 생략 (symbol={}, rank={})",
                        marketCountry,
                        symbol,
                        entry.rank()
                );
                continue;
            }

            stock.applyRanking(entry.rank(), entry.tradingAmount());
        }

        if (!unknownSymbols.isEmpty()) {
            logger.warn(
                    "StockRankingLoadService(marketCountry={}): 랭킹 {}건 중 {}건이 stock 테이블에 없어 생략됨. (symbols={})",
                    marketCountry,
                    entries.size(),
                    unknownSymbols.size(),
                    unknownSymbols
            );
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
