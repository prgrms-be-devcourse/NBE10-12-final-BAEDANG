package com.baedang.market.service;

import com.baedang.market.port.MarketDataPort;
import com.baedang.market.port.PriceQuote;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class QuoteSnapshotLoadService {

    private static final Logger log =
            LoggerFactory.getLogger(QuoteSnapshotLoadService.class);

    private final StockRepository stockRepository;
    private final MarketDataPort marketDataPort;
    private final QuoteSnapshotPersistenceService persistenceService;
    private final Clock clock;
    private final int universeSize;

    public QuoteSnapshotLoadService(
            StockRepository stockRepository,
            MarketDataPort marketDataPort,
            QuoteSnapshotPersistenceService persistenceService,
            Clock clock,
            @Value("${trading.universe-size}") int universeSize
    ) {
        this.stockRepository = stockRepository;
        this.marketDataPort = marketDataPort;
        this.persistenceService = persistenceService;
        this.clock = clock;
        this.universeSize = universeSize;
    }

    public int syncQuotes(MarketCountry marketCountry) {
        List<Stock> stocks = stockRepository.findRankedByMarketCountry(
                marketCountry,
                PageRequest.of(0, universeSize)
        );
        if (stocks.isEmpty()) {
            log.debug("동기화할 유니버스 종목이 없습니다: marketCountry={}", marketCountry);
            return 0;
        }

        List<String> symbols = stocks.stream()
                .map(Stock::getSymbol)
                .toList();
        List<PriceQuote> quotes = marketDataPort.fetchPrices(symbols);
        if (quotes.isEmpty()) {
            log.warn(
                    "외부 시세 응답이 비어 있습니다: marketCountry={}, symbolCount={}",
                    marketCountry,
                    symbols.size()
            );
            return 0;
        }

        OffsetDateTime collectedAt = clock.instant().atOffset(ZoneOffset.UTC);
        int updatedCount = persistenceService.saveOrUpdate(stocks, quotes, collectedAt);
        log.info(
                "시세 스냅샷 동기화 완료: marketCountry={}, updatedCount={}/{}",
                marketCountry,
                updatedCount,
                stocks.size()
        );
        return updatedCount;
    }
}
