package com.baedang.account.controller;

import com.baedang.account.dto.AccountSummaryResponse;
import com.baedang.account.dto.HoldingsResponse;
import com.baedang.account.service.AccountService;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AccountService accountService;

    @Test
    void 계좌_요약을_조회하면_원화_금액을_문자열로_응답한다() throws Exception {
        when(accountService.getSummary(7L)).thenReturn(sampleSummary());

        mockMvc.perform(get("/api/accounts/me").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1))
                .andExpect(jsonPath("$.roundNo").value(1))
                .andExpect(jsonPath("$.initialCash").value("50000000"))
                .andExpect(jsonPath("$.stockValue").value("2709000"))
                .andExpect(jsonPath("$.totalAsset").value("50949000"))
                .andExpect(jsonPath("$.unrealizedPnl").value("118728"))
                .andExpect(jsonPath("$.unrealizedPnlRate").value("0.0458"))
                .andExpect(jsonPath("$.exchangeRate").value("1400"));
    }

    @Test
    void 헤더가_없으면_설정된_시드_사용자로_조회한다() throws Exception {
        when(accountService.getSummary(1L)).thenReturn(sampleSummary());

        mockMvc.perform(get("/api/accounts/me"))
                .andExpect(status().isOk());

        verify(accountService).getSummary(1L);
    }

    @Test
    void ACTIVE_계좌가_없으면_404_와_에러코드를_응답한다() throws Exception {
        when(accountService.getSummary(1L))
                .thenThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        mockMvc.perform(get("/api/accounts/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("계좌 정보를 찾을 수 없어요"));
    }

    @Test
    void 보유_목록을_조회하면_종목별_평가정보를_문자열로_응답한다() throws Exception {
        when(accountService.getHoldings(7L)).thenReturn(sampleHoldings());

        mockMvc.perform(get("/api/accounts/me/holdings").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].symbol").value("005930"))
                .andExpect(jsonPath("$.items[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.items[0].currency").value("KRW"))
                .andExpect(jsonPath("$.items[0].quantity").value("6"))
                .andExpect(jsonPath("$.items[0].avgBuyPrice").value("228000"))
                .andExpect(jsonPath("$.items[0].lastPrice").value("241500"))
                .andExpect(jsonPath("$.items[0].evaluationAmount").value("1449000"))
                .andExpect(jsonPath("$.items[0].unrealizedPnl").value("81000"))
                .andExpect(jsonPath("$.items[0].unrealizedPnlRate").value("0.0592"))
                .andExpect(jsonPath("$.items[0].realtime").value(true));
    }

    @Test
    void 보유_목록도_헤더가_없으면_설정된_시드_사용자로_조회한다() throws Exception {
        when(accountService.getHoldings(1L)).thenReturn(sampleHoldings());

        mockMvc.perform(get("/api/accounts/me/holdings"))
                .andExpect(status().isOk());

        verify(accountService).getHoldings(1L);
    }

    private AccountSummaryResponse sampleSummary() {
        return new AccountSummaryResponse(
                1L, 1, "50000000", "48240000", "2709000", "50949000",
                "118728", "0.0458", "1400",
                OffsetDateTime.parse("2026-08-11T12:36:59+09:00"));
    }

    private HoldingsResponse sampleHoldings() {
        HoldingsResponse.Item item = new HoldingsResponse.Item(
                "005930", "삼성전자", "KRW", "6", "228000", "1", "241500",
                "1449000", "81000", "0.0592", true);
        return new HoldingsResponse(List.of(item), OffsetDateTime.parse("2026-08-11T12:36:59+09:00"));
    }
}
