package com.baedang.market.controller;

import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.market.dto.ExchangeRateLatestResponse;
import com.baedang.market.service.ExchangeRateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExchangeRateController.class)
class ExchangeRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @Test
    @DisplayName("base/quote 생략 시 기본값(USD/KRW)으로 조회한다")
    void t1_기본값_조회() throws Exception {
        ExchangeRateLatestResponse response = new ExchangeRateLatestResponse(
                "USD", "KRW", "1400.000000", "2.000000", "0.001431",
                OffsetDateTime.parse("2026-08-26T15:00:00+09:00"));
        when(exchangeRateService.getLatest("USD", "KRW")).thenReturn(response);

        mockMvc.perform(get("/api/exchange-rates/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.quoteCurrency").value("KRW"))
                .andExpect(jsonPath("$.rate").value("1400.000000"))
                .andExpect(jsonPath("$.changeAmount").value("2.000000"))
                .andExpect(jsonPath("$.changeRate").value("0.001431"));
    }

    @Test
    @DisplayName("base/quote 파라미터를 그대로 서비스에 전달한다")
    void t2_파라미터_전달() throws Exception {
        ExchangeRateLatestResponse response = new ExchangeRateLatestResponse(
                "EUR", "KRW", "1500.000000", "0", "0",
                OffsetDateTime.parse("2026-08-26T15:00:00+09:00"));
        when(exchangeRateService.getLatest("EUR", "KRW")).thenReturn(response);

        mockMvc.perform(get("/api/exchange-rates/latest").param("base", "EUR").param("quote", "KRW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("EUR"));
    }

    @Test
    @DisplayName("환율 정보가 없으면 404와 에러 코드를 반환한다")
    void t3_환율_없음() throws Exception {
        when(exchangeRateService.getLatest("USD", "KRW"))
                .thenThrow(new BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND));

        mockMvc.perform(get("/api/exchange-rates/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXCHANGE_RATE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("환율 정보를 가져올 수 없어요"));
    }
}
