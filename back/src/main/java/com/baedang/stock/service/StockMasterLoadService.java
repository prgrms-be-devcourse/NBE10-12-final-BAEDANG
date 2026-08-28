package com.baedang.stock.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockUniverseEntry;
import com.baedang.stock.port.SymbolInfoPort;
import com.baedang.stock.repository.StockRepository;
import com.baedang.standard.utils.Pacer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.springframework.transaction.annotation.Propagation.NEVER;

@Service
@Transactional(readOnly = true)
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

    private static final int TPS = 1;

    @Transactional(propagation = NEVER)
    public void loadAll() {
        Pacer pacer = Pacer.forTps(TPS);

        for (Map.Entry<String, MarketCountry> entry : MarketCountry.marketsNameMap().entrySet()) {
            String market = entry.getKey();
            MarketCountry marketCountry = entry.getValue();

            List<StockUniverseEntry> stocksFromPort = symbolInfoPort.fetchAllStocks(market);

            List<Stock> stocks = stocksFromPort.stream().map(stockFromPort -> Stock.create(
                    stockFromPort.symbol(),
                    marketCountry,
                    market,
                    stockFromPort.name(),
                    stockFromPort.isinCode(),
                    marketCountryToCurrency(marketCountry),
                    stockFromPort.securityType(),
                    stockFromPort.isCommonShare()
            )).toList();

            stockRepository.saveAll(stocks);

            pacer.pace();
        }
    }

    private String marketCountryToCurrency(MarketCountry marketCountry) {
        return switch (marketCountry) {
            case KR -> "KRW";
            case US -> "USD";
        };
    }
}