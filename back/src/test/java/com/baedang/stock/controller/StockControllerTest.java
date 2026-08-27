package com.baedang.stock.controller;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.dto.RankingResponse;
import com.baedang.stock.dto.StockSearchResponse;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.stock.entity.StockCategory;
import com.baedang.stock.service.RankingService;
import com.baedang.stock.service.StockSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockController.class)
public class StockControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockSearchService stockSearchService;

    @MockitoBean
    private RankingService rankingService;

    @Test
    @DisplayName("종목 검색 API가 검색 결과 반환")
    void t1() throws Exception {
        StockSearchResponse response = new StockSearchResponse(
                List.of(
                        new StockSearchResponse.Item(
                                "005930",
                                "삼성전자",
                                "SamsungElec",
                                "KOSPI",
                                MarketCountry.KR,
                                StockCategory.INDIVIDUAL
                        )
                )
        );

        when(stockSearchService.search("삼성", 10)).thenReturn(response);

        mockMvc.perform(
                        get("/api/stocks/search")
                                .param("q", "삼성")
                                .param("size", "10")
                                .contentType(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].symbol").value("005930"))
                .andExpect(jsonPath("$.items[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.items[0].englishName").value("SamsungElec"))
                .andExpect(jsonPath("$.items[0].market").value("KOSPI"))
                .andExpect(jsonPath("$.items[0].marketCountry").value("KR"))
                .andExpect(jsonPath("$.items[0].category").value("INDIVIDUAL"));

        verify(stockSearchService).search("삼성", 10);

    }

    @Test
    @DisplayName("size 생략시 기본값 10 사용")
    void t2() throws Exception {
        when(stockSearchService.search("삼성", 10)).thenReturn(new StockSearchResponse(List.of()));

        mockMvc.perform(
                        get("/api/stocks/search")
                                .param("q", "삼성")
                )
                .andExpect(status().isOk());

        verify(stockSearchService).search("삼성", 10);
    }

    @Test
    @DisplayName("검색어가 잘못되면 400 응답 반환")
    void t3() throws Exception {
        when(stockSearchService.search("삼", 10))
                .thenThrow(new BusinessException(ErrorCode.INVALID_QUERY));

        mockMvc.perform(
                        get("/api/stocks/search")
                                .param("q", "삼")
                                .param("size", "10")
                ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
                .andExpect(jsonPath("$.message").value("검색어는 2자 이상 입력해주세요"));
    }

    @Test
    @DisplayName("국내 종목 랭킹 반환")
    void t4() throws Exception {
        RankingResponse response = new RankingResponse(
                List.of(new RankingResponse.Item(
                        1,
                        "005930",
                        "삼성전자",
                        "KOSPI",
                        StockCategory.INDIVIDUAL,
                        false,
                        null,
                        "KRW",
                        "241500",
                        "236050",
                        "5450",
                        "0.023069",
                        "1240000000000",
                        null,
                        true
                )), "next-cursor", true
        );

        when(rankingService.getRankings("KR", 20, null)).thenReturn(response);

        mockMvc.perform(
                        get("/api/stocks/rankings")
                                .param("market", "KR")
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].symbol").value("005930"))
                .andExpect(jsonPath("$.items[0].lastPrice").value("241500"))
                .andExpect(jsonPath("$.items[0].changeAmount").value("5450"))
                .andExpect(jsonPath("$.items[0].realtime").value(true))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.hasNext").value(true));

        verify(rankingService).getRankings("KR", 20, null);
    }

    @Test
    @DisplayName("cursor를 다음 페이지 조회에 전달한다")
    void t5() throws Exception {
        when(rankingService.getRankings("US", 20, "cursor-value"))
                .thenReturn(new RankingResponse(List.of(),null,false));

        mockMvc.perform(
                get("/api/stocks/rankings")
                        .param("market", "US")
                        .param("size", "20")
                        .param("cursor", "cursor-value")
        ).andExpect(status().isOk());

        verify(rankingService).getRankings("US", 20, "cursor-value");
    }
}
