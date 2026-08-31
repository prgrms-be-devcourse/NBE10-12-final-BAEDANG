package com.baedang.stock.service;

import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.port.StockUniverseEntry;
import com.baedang.stock.port.SymbolInfoPort;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockMasterLoadServiceTest {
    @Mock
    private SymbolInfoPort symbolInfoPort;

    @Mock
    private StockRepository stockRepository;

    @Test
    @DisplayName("기존 심볼은 재사용하고 신규 심볼만 생성한다")
    void t1() {
        Stock existingStock = Stock.create(
                "005930",
                MarketCountry.KR,
                "KOSPI",
                "삼성전자",
                "KR7005930003",
                "KRW",
                "STOCK",
                true
        );

        when(symbolInfoPort.fetchAllStocks(anyString())).thenReturn(List.of());
        when(symbolInfoPort.fetchAllStocks("KOSPI")).thenReturn(List.of(
                new StockUniverseEntry(
                        "005930",
                        "삼성전자",
                        "STOCK",
                        true,
                        "KR7005930003"
                ),
                new StockUniverseEntry(
                        "000001",
                        "신규종목",
                        "STOCK",
                        true,
                        "KR7000000001"
                )
        ));

        when(stockRepository.findByMarketCountryAndSymbolIn(
                eq(MarketCountry.KR),
                anyCollection()
        )).thenReturn(List.of(existingStock));

        StockMasterLoadService service = new StockMasterLoadService(symbolInfoPort, stockRepository);

        service.loadAll();

        verify(stockRepository).saveAll(
                argThat((Iterable<Stock> stocks) -> {
                    List<Stock> savedStocks = new ArrayList<>();
                    stocks.forEach(savedStocks::add);

                    return savedStocks.size() == 2
                            && savedStocks.stream()
                            .anyMatch(stock -> stock == existingStock)
                            && savedStocks.stream()
                            .anyMatch(stock -> "000001".equals(stock.getSymbol()));
                })
        );

    }
}
