package com.baedang.trading.controller;

import com.baedang.auth.security.JwtAuthenticationFilter;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.global.config.SecurityConfig;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.stock.entity.MarketCountry;
import com.baedang.trading.dto.ExecutionResponse;
import com.baedang.trading.dto.LimitExecutionPreviewResponse;
import com.baedang.trading.dto.LimitOrderQuoteResponse;
import com.baedang.trading.dto.LimitOrderRequest;
import com.baedang.trading.dto.MarketOrderQuoteResponse;
import com.baedang.trading.dto.MarketOrderRequest;
import com.baedang.trading.dto.MarketOrderResponse;
import com.baedang.trading.dto.OrderDetailResponse;
import com.baedang.trading.dto.OrderExecutionsResponse;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.entity.OrderStatus;
import com.baedang.trading.entity.OrderType;
import com.baedang.trading.service.LimitOrderService;
import com.baedang.trading.service.MarketOrderQuoteService;
import com.baedang.trading.service.MarketOrderService;
import com.baedang.trading.service.OrderReadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean MarketOrderQuoteService marketOrderQuoteService;
    @MockitoBean MarketOrderService marketOrderService;
    @MockitoBean LimitOrderService limitOrderService;
    @MockitoBean OrderReadService orderReadService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"FILLED\"}", "{\"status\":\"CANCELED\",\"quantity\":\"1\"}", "{}", "[]"})
    void 취소는_허용된_단일필드만_받는다(String body) throws Exception {
        mockMvc.perform(patch("/api/orders/10")
                        .with(authenticatedUser(1L)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(limitOrderService);
    }

    @Test
    void 유효한_취소_요청은_성공_응답을_반환한다() throws Exception {
        when(limitOrderService.cancel(1L, 10L)).thenReturn(new OrderDetailResponse(
                10L, 1L, 100L,
                "005930", "삼성전자", MarketCountry.KR,
                OrderType.LIMIT, OrderSide.BUY, OrderStatus.CANCELED,
                "10", "0", "0",
                "240000", "KRW", "240000", "1", "0",
                "0", "0", "0", "0", null,
                OffsetDateTime.parse("2026-08-11T10:00:00+09:00"),
                OffsetDateTime.parse("2026-08-11T15:30:00+09:00"),
                OffsetDateTime.parse("2026-08-11T11:00:00+09:00")
        ));

        mockMvc.perform(patch("/api/orders/10")
                        .with(authenticatedUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(10))
                .andExpect(jsonPath("$.status").value("CANCELED"));
        verify(limitOrderService).cancel(1L, 10L);
    }

    @Test
    void 시장가_주문_견적의_금액과_수량을_JSON_문자열로_응답한다() throws Exception {
        when(marketOrderQuoteService.getQuote(1L, "005930", "KR", "BUY", "10"))
                .thenReturn(new MarketOrderQuoteResponse(
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

        mockMvc.perform(get("/api/orders/quote/market")
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
    void 지정가_견적을_200과_JSON으로_응답한다() throws Exception {
        when(limitOrderService.quote(1L, "005930", "KR", "BUY", "10", "240000", "KRW"))
                .thenReturn(new LimitOrderQuoteResponse(
                        "240000",
                        "KRW",
                        "240000",
                        "1",
                        true,
                        null,
                        "50000000",
                        "0",
                        OffsetDateTime.parse("2026-08-11T15:30:00+09:00"),
                        new LimitOrderQuoteResponse.Estimate("2400000", "240", "0", "2400240", "2400240"),
                        LimitExecutionPreviewResponse.unavailable(
                                LimitExecutionPreviewResponse.Status.UNAVAILABLE,
                                "NO_USABLE_BOOK",
                                Instant.parse("2026-08-11T06:00:00Z"))
                ));

        mockMvc.perform(get("/api/orders/quote/limit")
                        .with(authenticatedUser(1L))
                        .param("symbol", "005930")
                        .param("marketCountry", "KR")
                        .param("side", "BUY")
                        .param("quantity", "10")
                        .param("limitPrice", "240000")
                        .param("limitCurrency", "KRW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedLimitPrice").value("240000"))
                .andExpect(jsonPath("$.requestedLimitCurrency").value("KRW"))
                .andExpect(jsonPath("$.acceptable").value(true))
                .andExpect(jsonPath("$.limitEstimate.reservedCash").value("2400240"));
    }

    @Test
    void 시장가_체결결과를_201과_문자열_금액의_JSON으로_응답한다() throws Exception {
        MarketOrderRequest request = new MarketOrderRequest(
                10L, "018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f", "005930", "KR", "BUY", "10");
        when(marketOrderService.place(1L, request)).thenReturn(new MarketOrderResponse(
                1024L, "FILLED", "005930", MarketCountry.KR, "BUY", "10", "241500", "1",
                "2415000", "242", "0", "2415242",
                OffsetDateTime.parse("2026-08-11T12:36:59+09:00"),
                OffsetDateTime.parse("2026-08-11T12:37:02+09:00"),
                new MarketOrderResponse.AccountSummary("45824758")
        ));

        mockMvc.perform(post("/api/orders/market")
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
    void 지정가_주문접수_결과를_201과_OrderDetailResponse로_응답한다() throws Exception {
        LimitOrderRequest request = new LimitOrderRequest(
                10L, "018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f", "005930", "KR", "BUY", "10", "240000", "KRW");
        when(limitOrderService.place(1L, request)).thenReturn(new OrderDetailResponse(
                2048L, 10L, 1L, "005930", "삼성전자", MarketCountry.KR, OrderType.LIMIT, OrderSide.BUY, OrderStatus.PENDING,
                "10", "0", "10", "240000", "KRW", "240000", "1",
                "2400240", "0", "0", "0", "0", null,
                OffsetDateTime.parse("2026-08-11T12:37:00+09:00"),
                OffsetDateTime.parse("2026-08-11T15:30:00+09:00"),
                null
        ));

        mockMvc.perform(post("/api/orders/limit")
                        .with(authenticatedUser(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "clientOrderId": "018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f",
                                  "symbol": "005930",
                                  "marketCountry": "KR",
                                  "side": "BUY",
                                  "quantity": "10",
                                  "limitPrice": "240000",
                                  "limitCurrency": "KRW"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(2048))
                .andExpect(jsonPath("$.symbol").value("005930"))
                .andExpect(jsonPath("$.name").value("삼성전자"))
                .andExpect(jsonPath("$.marketCountry").value("KR"))
                .andExpect(jsonPath("$.currency").doesNotExist())
                .andExpect(jsonPath("$.activeRemainingQuantity").value("10"))
                .andExpect(jsonPath("$.remainingQuantity").doesNotExist())
                .andExpect(jsonPath("$.orderType").value("LIMIT"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.limitPrice").value("240000"))
                .andExpect(jsonPath("$.reservedCash").value("2400240"));
    }

    @Test
    void 시장가_주문_업무거절을_표준_에러응답으로_변환한다() throws Exception {
        MarketOrderRequest request = new MarketOrderRequest(
                10L, "018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f", "005930", "KR", "BUY", "10");
        when(marketOrderService.place(1L, request))
                .thenThrow(new BusinessException(
                        ErrorCode.INSUFFICIENT_CASH,
                        Map.of("retryPolicy", "NEW_CLIENT_ORDER_ID")));

        mockMvc.perform(post("/api/orders/market")
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
    void 체결_종목정보는_응답_상위에만_제공한다() throws Exception {
        var execution = new ExecutionResponse(5L, 1, "2", "100.00", "1400",
                "280000", "28", "0", "280028", "49719972",
                OffsetDateTime.parse("2026-09-07T01:00:00Z"));
        when(orderReadService.executions(1L, 10L, null, 20)).thenReturn(
                new OrderExecutionsResponse(10L,
                        new OrderExecutionsResponse.StockSummary("INTC", "인텔", MarketCountry.US),
                        List.of(execution), "next-page", true));
        mockMvc.perform(get("/api/orders/10/executions").with(authenticatedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(10))
                .andExpect(jsonPath("$.stock.symbol").value("INTC"))
                .andExpect(jsonPath("$.stock.name").value("인텔"))
                .andExpect(jsonPath("$.stock.marketCountry").value("US"))
                .andExpect(jsonPath("$.items[0].executionId").value(5))
                .andExpect(jsonPath("$.items[0].price").value("100.00"))
                .andExpect(jsonPath("$.items[0].symbol").doesNotExist())
                .andExpect(jsonPath("$.items[0].name").doesNotExist())
                .andExpect(jsonPath("$.items[0].marketCountry").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("next-page"))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @ParameterizedTest
    @CsvSource({"/api/orders/abc,orderId", "/api/orders/1/executions?size=abc,size"})
    void 잘못된_파라미터_타입은_400과_문제필드를_반환한다(String url, String field) throws Exception {
        mockMvc.perform(get(url).with(authenticatedUser(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data.field").value(field));
    }

    @Test
    void 누락된_주문_파라미터를_data_field로_응답한다() throws Exception {
        mockMvc.perform(get("/api/orders/quote/market")
                        .with(authenticatedUser(1L))
                        .param("marketCountry", "KR")
                        .param("side", "BUY")
                        .param("quantity", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data.field").value("symbol"));
        verifyNoInteractions(marketOrderQuoteService);
    }

    @Test
    void 인증_없이_주문_견적을_요청하면_401을_응답한다() throws Exception {
        mockMvc.perform(get("/api/orders/quote/market")
                        .param("symbol", "005930")
                        .param("marketCountry", "KR")
                        .param("side", "BUY")
                        .param("quantity", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 주문_JSON을_읽을_수_없으면_400_표준에러를_응답한다() throws Exception {
        mockMvc.perform(post("/api/orders/market")
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
