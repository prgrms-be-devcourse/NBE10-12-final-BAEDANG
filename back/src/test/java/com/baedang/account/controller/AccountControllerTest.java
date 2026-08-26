package com.baedang.account.controller;

import com.baedang.account.dto.AccountSummaryResponse;
import com.baedang.account.service.AccountService;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

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

    private AccountSummaryResponse sampleSummary() {
        return new AccountSummaryResponse(
                1L, 1, "50000000", "48240000", "2709000", "50949000",
                "118728", "0.0458", "1400",
                OffsetDateTime.parse("2026-08-11T12:36:59+09:00"));
    }
}
