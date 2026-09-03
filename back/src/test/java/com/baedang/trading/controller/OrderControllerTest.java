package com.baedang.trading.controller;

import com.baedang.auth.security.JwtAuthenticationFilter;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.config.SecurityConfig;
import com.baedang.trading.dto.OrderQuoteResponse;
import com.baedang.trading.dto.OrderResponse;
import com.baedang.trading.dto.PlaceOrderRequest;
import com.baedang.trading.entity.OrderSide;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.service.MarketOrderService;
import com.baedang.trading.service.OrderQuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean OrderQuoteService orderQuoteService;
    @MockitoBean MarketOrderService marketOrderService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    void 주문_견적의_금액과_수량을_JSON_문자열로_응답한다() throws Exception {
        when(orderQuoteService.getQuote(1L, "005930", "KR", "BUY", "10"))
                .thenReturn(new OrderQuoteResponse(
                        "005930",
                        MarketCountry.KR,
                        OrderSide.BUY,
                        "10",
                        "241500",
                        "1",
                        "2415000",
                        "242",
                        "0",
                        "2415242",
                        "48240000",
                        OffsetDateTime.parse("2026-08-11T12:36:59+09:00"),
                        true,
                        null
                ));

        mockMvc.perform(get("/api/orders/quote")
                        .with(authenticatedUser(1L))
                        .param("symbol", "005930")
                        .param("marketCountry", "KR")
                        .param("side", "BUY")
                        .param("quantity", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketCountry").value("KR"))
                .andExpect(jsonPath("$.quantity").value("10"))
                .andExpect(jsonPath("$.grossAmount").value("2415000"))
                .andExpect(jsonPath("$.fee").value("242"))
                .andExpect(jsonPath("$.tax").value("0"))
                .andExpect(jsonPath("$.netAmount").value("2415242"))
                .andExpect(jsonPath("$.executable").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void 시장가_주문을_즉시_체결하고_금액을_문자열로_응답한다() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(
                10L, "018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f", "005930", "KR", "BUY", "10");
        when(marketOrderService.place(1L, request)).thenReturn(new OrderResponse(
                1024L, "FILLED", "005930", MarketCountry.KR, "BUY", "10", "241500", "1",
                "2415000", "242", "0", "2415242",
                OffsetDateTime.parse("2026-08-11T12:36:59+09:00"),
                OffsetDateTime.parse("2026-08-11T12:37:02+09:00"),
                new OrderResponse.AccountSummary("45824758")
        ));

        mockMvc.perform(post("/api/orders")
                        .with(authenticatedUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "clientOrderId": "018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f",
                                  "symbol": "005930",
                                  "marketCountry": "KR",
                                  "side": "BUY",
                                  "quantity": "10"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.marketCountry").value("KR"))
                .andExpect(jsonPath("$.quantity").value("10"))
                .andExpect(jsonPath("$.grossAmount").value("2415000"))
                .andExpect(jsonPath("$.netAmount").value("2415242"))
                .andExpect(jsonPath("$.account.cashBalanceAfter").value("45824758"))
                .andExpect(jsonPath("$.account.totalAsset").doesNotExist());
    }

    @Test
    void 시장가_주문_업무거절을_표준_에러응답으로_변환한다() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(
                10L, "018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f", "005930", "KR", "BUY", "10");
        when(marketOrderService.place(1L, request))
                .thenThrow(new BusinessException(
                        ErrorCode.INSUFFICIENT_CASH,
                        Map.of("retryPolicy", "NEW_CLIENT_ORDER_ID")));

        mockMvc.perform(post("/api/orders")
                        .with(authenticatedUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "clientOrderId": "018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f",
                                  "symbol": "005930",
                                  "marketCountry": "KR",
                                  "side": "BUY",
                                  "quantity": "10"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CASH"))
                .andExpect(jsonPath("$.message").value("주문가능금액이 부족해요"))
                .andExpect(jsonPath("$.data.retryPolicy").value("NEW_CLIENT_ORDER_ID"));
    }

    @Test
    void 누락된_주문_파라미터를_data_field로_응답한다() throws Exception {
        when(orderQuoteService.getQuote(1L, null, "KR", "BUY", "1"))
                .thenThrow(new BusinessException(
                        ErrorCode.INVALID_INPUT, Map.of("field", "symbol")));

        mockMvc.perform(get("/api/orders/quote")
                        .with(authenticatedUser(1L))
                        .param("marketCountry", "KR")
                        .param("side", "BUY")
                        .param("quantity", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data.field").value("symbol"));
    }

    @Test
    void 인증_없이_주문_견적을_요청하면_401을_응답한다() throws Exception {
        mockMvc.perform(get("/api/orders/quote")
                        .param("symbol", "005930")
                        .param("marketCountry", "KR")
                        .param("side", "BUY")
                        .param("quantity", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 주문_JSON을_읽을_수_없으면_400_표준에러를_응답한다() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .with(authenticatedUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientOrderId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
    private static RequestPostProcessor authenticatedUser(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of()));
    }

}
