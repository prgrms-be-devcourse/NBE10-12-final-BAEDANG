package com.baedang.stock.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockUniverseEntry;
import com.baedang.stock.port.SymbolInfoPort;
import com.baedang.stock.repository.StockRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StockMasterLoadService {
    private final SymbolInfoPort symbolInfoPort;
    private final StockRepository stockRepository;

    public StockMasterLoadService(
            SymbolInfoPort symbolInfoPort,
            StockRepository stockRepository
    ) {
        this.symbolInfoPort = symbolInfoPort;
        this.stockRepository = stockRepository;
    }

    public void loadAll() {

        for (Map.Entry<String, MarketCountry> entry : MarketCountry.marketsNameMap().entrySet()) {
            String market = entry.getKey();
            MarketCountry marketCountry = entry.getValue();

            List<StockUniverseEntry> stocksFromPort = symbolInfoPort.fetchAllStocks(market);

            Map<String, StockUniverseEntry> uniqueStocks = stocksFromPort.stream()
                    .collect(Collectors.toMap(
                            stock -> normalizeSymbol(stock.symbol()),
                            Function.identity(),
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));

            List<String> symbols = uniqueStocks.keySet().stream().toList();

            Map<String, Stock> existingStocks = new LinkedHashMap<>();

            if (!symbols.isEmpty()) {
                existingStocks.putAll(
                        stockRepository
                                .findByMarketCountryAndSymbolIn(marketCountry, symbols)
                                .stream().collect(Collectors.toMap(
                                        stock -> normalizeSymbol(
                                                stock.getSymbol()
                                        ),
                                        Function.identity(),
                                        (left, right) -> left
                                ))
                );
            }

            // 기존 종목은 제외하고, 신규 종목만 생성하여 저장한다.
            List<Stock> newStocks = uniqueStocks.entrySet()
                    .stream()
                    .filter(universeEntry -> !existingStocks.containsKey(universeEntry.getKey()))
                    .map(universeEntry -> Stock.create(
                            universeEntry.getKey(),
                            marketCountry,
                            market,
                            universeEntry.getValue().name(),
                            universeEntry.getValue().isinCode(),
                            marketCountryToCurrency(marketCountry),
                            universeEntry.getValue().securityType(),
                            universeEntry.getValue().isCommonShare()
                    ))
                    .toList();

            if (!newStocks.isEmpty()) stockRepository.saveAll(newStocks);

        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String marketCountryToCurrency(MarketCountry marketCountry) {
        return switch (marketCountry) {
            case KR -> "KRW";
            case US -> "USD";
        };
    }
}