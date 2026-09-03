package com.baedang.account.controller;

import com.baedang.account.dto.AccountResetResponse;
import com.baedang.account.dto.AccountSummaryResponse;
import com.baedang.account.dto.HoldingsResponse;
import com.baedang.account.dto.LedgerResponse;
import com.baedang.account.service.AccountResetService;
import com.baedang.account.service.AccountService;
import com.baedang.account.service.LedgerQueryService;
import com.baedang.auth.security.JwtAuthenticationFilter;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.global.config.SecurityConfig;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class AccountControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AccountService accountService;
    @MockitoBean AccountResetService accountResetService;
    @MockitoBean LedgerQueryService ledgerQueryService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    void 포트폴리오를_초기화하면_새_회차와_원화_금액을_문자열로_응답한다() throws Exception {
        when(accountResetService.reset(7L, 1L))
                .thenReturn(new AccountResetResponse(2L, 2, "50000000", "50000000"));

        mockMvc.perform(post("/api/accounts/me/reset")
                        .with(authenticatedUser(7L))
                        .contentType("application/json")
                        .content("{\"accountId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(2))
                .andExpect(jsonPath("$.roundNo").value(2))
                .andExpect(jsonPath("$.initialCash").value("50000000"))
                .andExpect(jsonPath("$.cashBalance").value("50000000"));
    }

    @Test
    void 인증_없이_초기화하면_401을_응답한다() throws Exception {
        mockMvc.perform(post("/api/accounts/me/reset")
                        .contentType("application/json")
                        .content("{\"accountId\":10}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 초기화_요청에_계좌_ID가_없으면_400을_응답한다() throws Exception {
        mockMvc.perform(post("/api/accounts/me/reset")
                        .with(authenticatedUser(7L))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data.accountId").value("현재 계좌 ID는 필수입니다"));
    }

    @Test
    void 계좌_요약을_조회하면_원화_금액을_문자열로_응답한다() throws Exception {
        when(accountService.getSummary(7L)).thenReturn(sampleSummary());

        mockMvc.perform(get("/api/accounts/me").with(authenticatedUser(7L)))
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
    void 인증_없이_계좌를_조회하면_401을_응답한다() throws Exception {
        mockMvc.perform(get("/api/accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void ACTIVE_계좌가_없으면_404_와_에러코드를_응답한다() throws Exception {
        when(accountService.getSummary(1L))
                .thenThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        mockMvc.perform(get("/api/accounts/me").with(authenticatedUser(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("계좌 정보를 찾을 수 없어요"));
    }

    @Test
    void 보유_목록을_조회하면_종목별_평가정보를_문자열로_응답한다() throws Exception {
        when(accountService.getHoldings(7L)).thenReturn(sampleHoldings());

        mockMvc.perform(get("/api/accounts/me/holdings").with(authenticatedUser(7L)))
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
    void 인증_없이_보유목록을_조회하면_401을_응답한다() throws Exception {
        mockMvc.perform(get("/api/accounts/me/holdings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 체결_내역을_조회하면_커서와_항목을_응답한다() throws Exception {
        when(ledgerQueryService.getLedger(7L, null, null, null)).thenReturn(sampleLedger());

        mockMvc.perform(get("/api/accounts/me/ledger").with(authenticatedUser(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].entryId").value(3041))
                .andExpect(jsonPath("$.items[0].entryType").value("BUY"))
                .andExpect(jsonPath("$.items[0].amount").value("-2415242"))
                .andExpect(jsonPath("$.items[0].orderId").value(1024))
                .andExpect(jsonPath("$.items[0].symbol").value("005930"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").value("eyJlbnRyeUlkIjozMDQwfQ"));
    }

    @Test
    void 체결_내역_파라미터를_서비스로_그대로_전달한다() throws Exception {
        when(ledgerQueryService.getLedger(1L, "eyJlbnRyeUlkIjozMDQwfQ", 10, "BUY")).thenReturn(sampleLedger());

        mockMvc.perform(get("/api/accounts/me/ledger")
                        .with(authenticatedUser(1L))
                        .param("cursor", "eyJlbnRyeUlkIjozMDQwfQ")
                        .param("size", "10")
                        .param("entryType", "BUY"))
                .andExpect(status().isOk());

        verify(ledgerQueryService).getLedger(1L, "eyJlbnRyeUlkIjozMDQwfQ", 10, "BUY");
    }

    @Test
    void 위조된_사용자_헤더보다_인증_principal을_신뢰한다() throws Exception {
        when(accountService.getSummary(7L)).thenReturn(sampleSummary());

        mockMvc.perform(get("/api/accounts/me")
                        .header("X-User-Id", "999")
                        .with(authenticatedUser(7L)))
                .andExpect(status().isOk());

        verify(accountService).getSummary(7L);
    }

    private static RequestPostProcessor authenticatedUser(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of()));
    }

    private LedgerResponse sampleLedger() {
        LedgerResponse.Item item = new LedgerResponse.Item(
                3041L, "BUY", "-2415242", "47584758", "1", "삼성전자 10주 @ 241,500 (수수료 포함)",
                1024L, "005930", "삼성전자", OffsetDateTime.parse("2026-08-11T03:37:02Z"));
        return new LedgerResponse(List.of(item), "eyJlbnRyeUlkIjozMDQwfQ", true);
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

    @Test
    void 보호_API_CORS_preflight를_인증없이_허용한다() throws Exception {
        mockMvc.perform(options("/api/accounts/me")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:3000"));
    }
}
