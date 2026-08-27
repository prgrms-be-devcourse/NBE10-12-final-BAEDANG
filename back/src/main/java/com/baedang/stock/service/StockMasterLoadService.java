package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockUniverseEntry;
import com.baedang.stock.port.SymbolInfoPort;
import com.baedang.stock.repository.StockRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.baedang.stock.entity.MarketCountry.KR;
import static com.baedang.stock.entity.MarketCountry.US;
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
    private static final Map<String, MarketCountry> markets = Map.of(
            "KOSPI", KR,
            "KOSDAQ", KR,
            "KR_ETC", KR,

            "NYSE", US,
            "NASDAQ", US,
            "AMEX", US,
            "US_ETC", US
    );

    @Transactional(propagation = NEVER)
    public void loadAll() {
        for (Map.Entry<String, MarketCountry> entry : markets.entrySet()) {
            String market = entry.getKey();
            MarketCountry marketCountry = entry.getValue();

            List<StockUniverseEntry> stocksFromPort = symbolInfoPort.fetchAllStocks(market);

            List<Stock> stocks = stocksFromPort.stream().map(stockFormPort -> Stock.create(
                    stockFormPort.symbol(),
                    marketCountry,
                    market,
                    stockFormPort.name(),
                    stockFormPort.isinCode(),
                    marketCountryToCurrency(marketCountry),
                    stockFormPort.securityType(),
                    stockFormPort.isCommonShare()
            )).toList();

            stockRepository.saveAll(stocks);

            paceControl();
        }
    }

    private void paceControl() {
        int interval = 1000 % TPS == 0 ? 1000 / TPS : 1000 / TPS + 1;
        try {
            Thread.sleep(interval);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Interruption during pace control");
        }
    }

    private String marketCountryToCurrency(MarketCountry marketCountry) {
        return switch (marketCountry) {
            case KR -> "KRW";
            case US -> "USD";
        };
    }
}