package com.baedang.stock.service;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StockRankingLoadService {

    private static final Logger log = LoggerFactory.getLogger(StockRankingLoadService.class);

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

    // 두 시장의 랭킹 적재 스케줄 시간이 다르므로 이 메서드는 테스트 용도로 사용한다.
    public void loadAll() {
        for (MarketCountry marketCountry : MarketCountry.values()) {
            load(marketCountry);
        }
    }

    public void load(MarketCountry marketCountry) {
        RankingSnapshot snapshot = rankingPort.fetchRanking(marketCountry);

        // 보통 휴장일에 빈 배열이 온다. 예외가 있을 수 있음.
        if (snapshot.entries().isEmpty()) {
            log.warn(
                    "StockRankingLoadService(marketCountry={}): 랭킹 집계 결과가 비어 있어 직전 유니버스를 유지합니다.",
                    marketCountry
            );
        } else {
            self.applyRanking(marketCountry, snapshot.entries());
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
                log.warn(
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
            log.warn(
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
