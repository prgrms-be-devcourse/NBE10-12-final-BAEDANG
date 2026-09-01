package com.baedang.stock.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockInfo;
import com.baedang.stock.port.SymbolInfoPort;
import com.baedang.stock.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.baedang.stock.entity.ListingStatus.ACTIVE;
import static com.baedang.stock.entity.ListingStatus.DELISTED;

@Service
public class StockMasterDetailLoadService {
    private final SymbolInfoPort symbolInfoPort;
    private final StockRepository stockRepository;

    public StockMasterDetailLoadService(
            SymbolInfoPort symbolInfoPort,
            StockRepository stockRepository
    ) {
        this.symbolInfoPort = symbolInfoPort;
        this.stockRepository = stockRepository;
    }

    private static final int CHUNK_SIZE = 200;

    private static final Logger logger = LoggerFactory.getLogger(StockMasterDetailLoadService.class);

    public void loadAll() {
        int pageNumber = 0;
        while (true) {
            List<Stock> stocks = stockRepository
                    .findAllByOrderByStockIdAsc(PageRequest.of(pageNumber, CHUNK_SIZE))
                    .getContent();
            if (stocks.isEmpty()) break;

            List<StockInfo> stocksFromPort = symbolInfoPort.fetchStocks(stocks.stream().map(Stock::getSymbol).toList());

            // 응답은 요청보다 작을 수 있다. — 인덱스가 아니라 심볼로 짝짓는다.
            Map<String, StockInfo> stocksFromPortSymbolMap = stocksFromPort.stream()
                    .collect(Collectors.toMap(
                            stockFromPort -> stockFromPort.symbol().trim().toUpperCase(Locale.ROOT),
                            stockFromPort -> stockFromPort,
                            (left, right) -> left
                    ));

            for (Stock stock : stocks) {
                StockInfo stockFromPort = stocksFromPortSymbolMap.get(stock.getSymbol());

                // 응답에 없는 심볼(상장예정 등)은 1단계가 넣은 상태 그대로 둔다
                if (stockFromPort == null) {
                    logger.info("상세 응답 누락, 1단계 값 유지: {}", stock.getSymbol());
                    continue;
                }

                stock.updateMasterInfo(
                        MarketCountry.fromMarket(stockFromPort.market()), stockFromPort.market(),
                        stockFromPort.name(), stockFromPort.englishName(),
                        stockFromPort.isinCode(),
                        stockFromPort.currency(),
                        stockFromPort.securityType(),
                        stockFromPort.leverageFactor(),
                        stockFromPort.isCommonShare(),
                        stockFromPort.sharesOutstanding(),
                        stockFromPort.listDate(),
                        stockFromPort.delistDate(),
                        DELISTED.name().equals(stockFromPort.status()) ? DELISTED : ACTIVE
                );

                StockInfo.KrMarketDetail krMarketDetail = stockFromPort.krMarketDetail();   // 미국 종목은 null

                stock.updateFlags(
                        krMarketDetail != null && krMarketDetail.krxTradingSuspended(),
                        krMarketDetail != null && krMarketDetail.liquidationTrading(),
                        false   // 1주차엔 is_warned 를 채우는 배치가 없어 항상 false (2주차에 시그니처 분리 필요)
                );
            }

            stockRepository.saveAll(stocks);

            pageNumber++;
        }
    }
}
