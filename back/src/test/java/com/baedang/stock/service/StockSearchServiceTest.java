package com.baedang.stock.service;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.dto.StockSearchResponse;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.Stock;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class StockSearchServiceTest {

    @Mock
    private StockRepository stockRepository;

    @Mock
    private Stock stock;

    @InjectMocks
    private StockSearchService stockSearchService;

    @Test
    @DisplayName("검색어를 정규화해 종목 조회")
    void t1() {
        StockSearchService service = new StockSearchService(stockRepository);

        when(stockRepository.searchByKeyword("samsungelec"))
                .thenReturn(List.of(stock));

        when(stock.getSymbol()).thenReturn("005930");
        when(stock.getName()).thenReturn("삼성전자");
        when(stock.getEnglishName()).thenReturn("SamsungElec");
        when(stock.getMarket()).thenReturn("KOSPI");
        when(stock.getMarketCountry()).thenReturn(MarketCountry.KR);
        when(stock.getStockCategory()).thenReturn(StockCategory.INDIVIDUAL);

        StockSearchResponse response = service.search(" Samsung Elec ",10);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).symbol()).isEqualTo("005930");
        assertThat(response.items().get(0).name()).isEqualTo("삼성전자");
    }

    @Test
    @DisplayName("검색어가 2자 미만이면 예외 발생")
    void t2(){
        StockSearchService service = new StockSearchService(stockRepository);
        assertThatThrownBy(() -> service.search("삼",10))
                .isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUERY);
    }

    @Test
    @DisplayName("검색어 null이면 예외 발생")
    void t3(){
        StockSearchService service = new StockSearchService(stockRepository);

        assertThatThrownBy(() -> service.search(null,10))
                .isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUERY);
    }

    @Test
    @DisplayName("size가 범위를 벗어나면 예외 발생")
    void t4(){
        StockSearchService service = new StockSearchService(stockRepository);

        assertThatThrownBy(() -> service.search("삼성",101))
                .isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
