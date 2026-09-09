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
import static org.mockito.Mockito.verifyNoInteractions;
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

        when(stockRepository.searchByJamo("samsungelec"))
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
    @DisplayName("1자 검색어도 조회한다 (#148)")
    void t2(){
        StockSearchService service = new StockSearchService(stockRepository);

        when(stockRepository.searchByJamo("삼")).thenReturn(List.of());

        assertThat(service.search("삼",10).items()).isEmpty();
    }

    @Test
    @DisplayName("정규화 후 빈 검색어면 예외 발생")
    void t2_1(){
        StockSearchService service = new StockSearchService(stockRepository);

        assertThatThrownBy(() -> service.search(" %_ ",10))
                .isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUERY);
    }

    /**
     * 빈 결과만 보면 DB 가 0건을 준 건지 앞에서 걸러낸 건지 구분되지 않으므로,
     * 조회 자체가 일어나지 않았음을 확인합니다.
     */
    @Test
    @DisplayName("음절 경계를 깨는 자모는 조회하지 않고 0건 응답")
    void t2_2(){
        StockSearchService service = new StockSearchService(stockRepository);

        assertThat(service.search("ㅊ김",10).items()).isEmpty();
        assertThat(service.search("김ㅏ",10).items()).isEmpty();
        assertThat(service.search("ㅏ",10).items()).isEmpty();

        verifyNoInteractions(stockRepository);
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

    /**
     * 이름순 정렬만 남으면 '삼성전자' 가 앞이므로, 완전일치가 이름순을 이겨야 검증이 된다.
     */
    @Test
    @DisplayName("초성 검색은 초성 완전일치를 상위로 (#148)")
    void t5() {
        StockSearchService service = new StockSearchService(stockRepository);

        Stock exact = stubStock("석삼", "ㅅㅅ");
        Stock partial = stubStock("삼성전자", "ㅅㅅㅈㅈ");

        when(stockRepository.searchByChosung("ㅅㅅ")).thenReturn(List.of(partial, exact));

        assertThat(service.search("ㅅㅅ", 10).items())
                .extracting(StockSearchResponse.Item::name)
                .containsExactly("석삼", "삼성전자");
    }

    @Test
    @DisplayName("미완성 입력도 접두 일치를 상위로 (#148)")
    void t6() {
        StockSearchService service = new StockSearchService(stockRepository);

        Stock prefix = stubStock("삼성전자", null);
        Stock contains = stubStock("가나삼성", null);

        when(stockRepository.searchByJamo("삼ㅅ")).thenReturn(List.of(contains, prefix));

        assertThat(service.search("삼ㅅ", 10).items())
                .extracting(StockSearchResponse.Item::name)
                .containsExactly("삼성전자", "가나삼성");
    }

    private Stock stubStock(String name, String chosung) {
        Stock s = org.mockito.Mockito.mock(Stock.class);
        org.mockito.Mockito.lenient().when(s.getName()).thenReturn(name);
        org.mockito.Mockito.lenient().when(s.getNameChosung()).thenReturn(chosung);
        return s;
    }
}
