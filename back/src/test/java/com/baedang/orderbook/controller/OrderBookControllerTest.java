package com.baedang.orderbook.controller;

import com.baedang.auth.security.JwtAuthenticationFilter;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.global.config.SecurityConfig;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.orderbook.dto.OrderBookLevelResponse;
import com.baedang.orderbook.dto.OrderBookResponse;
import com.baedang.orderbook.service.OrderBookQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderBookController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class OrderBookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderBookQueryService queryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    private static OrderBookResponse sampleResponse() {
        List<OrderBookLevelResponse> asks = new ArrayList<>(10);
        List<OrderBookLevelResponse> bids = new ArrayList<>(10);
        for (int i = 1; i <= 10; i++) {
            asks.add(new OrderBookLevelResponse(i, String.valueOf(70000 + i * 100), "1000"));
            bids.add(new OrderBookLevelResponse(i, String.valueOf(70000 - i * 100), "1000"));
        }
        return new OrderBookResponse(
                "005930", "KR", 1042L, 0L, "70000", "KRW",
                Instant.parse("2026-09-03T01:00:00Z"), Instant.parse("2026-09-03T01:00:03Z"),
                true, "현재가 기반 가상 호가·가상 잔량", asks, bids
        );
    }

    @Test
    @DisplayName("활성 호가의 ASK와 BID 10개를 한번에 반환한다")
    void 활성_호가의_ASK와_BID_10개를_한번에_반환한다() throws Exception {
        when(queryService.getOrderBook("005930", "KR")).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/stocks/005930/orderbook")
                        .param("marketCountry", "KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("005930"))
                .andExpect(jsonPath("$.marketCountry").value("KR"))
                .andExpect(jsonPath("$.bookVersion").value(1042))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.basePrice").value("70000"))
                .andExpect(jsonPath("$.virtual").value(true))
                .andExpect(jsonPath("$.description").value("현재가 기반 가상 호가·가상 잔량"))
                .andExpect(jsonPath("$.asks.length()").value(10))
                .andExpect(jsonPath("$.bids.length()").value(10));
    }

    @Test
    @DisplayName("잘못된 marketCountry는 400이다")
    void 잘못된_marketCountry는_400이다() throws Exception {
        when(queryService.getOrderBook("005930", "EU"))
                .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        mockMvc.perform(get("/api/stocks/005930/orderbook")
                        .param("marketCountry", "EU"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("marketCountry 누락 시 400이다")
    void marketCountry_누락은_400이다() throws Exception {
        when(queryService.getOrderBook("005930", null))
                .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        mockMvc.perform(get("/api/stocks/005930/orderbook"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("서로 다른 사용자도 같은 공유 호가를 조회한다")
    void 서로_다른_사용자도_같은_공유_호가를_조회한다() throws Exception {
        when(queryService.getOrderBook("005930", "KR")).thenReturn(sampleResponse());

        MvcResult first = mockMvc.perform(get("/api/stocks/005930/orderbook")
                        .with(user("first-user"))
                        .param("marketCountry", "KR"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(get("/api/stocks/005930/orderbook")
                        .with(user("second-user"))
                        .param("marketCountry", "KR"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("활성 호가가 없으면 503이다")
    void 활성_호가가_없으면_503이다() throws Exception {
        when(queryService.getOrderBook("005930", "KR"))
                .thenThrow(new BusinessException(ErrorCode.ORDER_BOOK_UNAVAILABLE));

        mockMvc.perform(get("/api/stocks/005930/orderbook")
                        .param("marketCountry", "KR"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_BOOK_UNAVAILABLE"));
    }

    @Test
    @DisplayName("종목이 없으면 404이다")
    void 종목이_없으면_404이다() throws Exception {
        when(queryService.getOrderBook("NONEXIST", "KR"))
                .thenThrow(new BusinessException(ErrorCode.STOCK_NOT_FOUND));

        mockMvc.perform(get("/api/stocks/NONEXIST/orderbook")
                        .param("marketCountry", "KR"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
    }
}
