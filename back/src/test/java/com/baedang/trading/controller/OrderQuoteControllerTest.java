package com.baedang.trading.controller;

import com.baedang.trading.dto.OrderQuoteResponse;
import com.baedang.trading.entity.OrderSide;
import com.baedang.trading.service.OrderQuoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderQuoteControllerTest {

    @Mock OrderQuoteService orderQuoteService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OrderQuoteController controller = new OrderQuoteController(orderQuoteService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 금액과_수량을_JSON_문자열로_응답한다() throws Exception {
        when(orderQuoteService.getQuote(1L, "005930", "BUY", "10"))
                .thenReturn(new OrderQuoteResponse(
                        "005930",
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
                        .header("X-User-Id", "1")
                        .param("symbol", "005930")
                        .param("side", "BUY")
                        .param("quantity", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value("10"))
                .andExpect(jsonPath("$.grossAmount").value("2415000"))
                .andExpect(jsonPath("$.fee").value("242"))
                .andExpect(jsonPath("$.tax").value("0"))
                .andExpect(jsonPath("$.netAmount").value("2415242"))
                .andExpect(jsonPath("$.executable").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }
}
