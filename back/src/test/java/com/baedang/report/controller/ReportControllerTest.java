package com.baedang.report.controller;

import com.baedang.auth.security.JwtAuthenticationFilter;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.global.config.SecurityConfig;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.report.dto.PersonalityReportResponse;
import com.baedang.report.service.PersonalityReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class ReportControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean PersonalityReportService personalityReportService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    void 리포트를_조회하면_유형과_비중을_문자열로_응답한다() throws Exception {
        when(personalityReportService.getReport(7L)).thenReturn(sampleReport());

        mockMvc.perform(get("/api/reports/me").with(authenticatedUser(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(10))
                .andExpect(jsonPath("$.returnRate").value("0.06"))
                .andExpect(jsonPath("$.classified").value(true))
                .andExpect(jsonPath("$.typeCode").value("CKSB"))
                .andExpect(jsonPath("$.typeLabel").value("집중·국내·개별주·안정형"))
                .andExpect(jsonPath("$.shares.domestic").value("0.6364"))
                .andExpect(jsonPath("$.holdingCount").value(2))
                .andExpect(jsonPath("$.holdingPeriodWeeks").value(4))
                .andExpect(jsonPath("$.longHeldStocks[0].symbol").value("005930"))
                .andExpect(jsonPath("$.longHeldStocks[0].returnRate").value("0.0592"));
    }

    @Test
    void 인증_없이_조회하면_401을_응답한다() throws Exception {
        mockMvc.perform(get("/api/reports/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void ACTIVE_계좌가_없으면_404_와_에러코드를_응답한다() throws Exception {
        when(personalityReportService.getReport(1L))
                .thenThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        mockMvc.perform(get("/api/reports/me").with(authenticatedUser(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    private static RequestPostProcessor authenticatedUser(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private PersonalityReportResponse sampleReport() {
        return new PersonalityReportResponse(
                10L, 1, "50000000", "20000000", "33000000", "53000000", "3000000", "0.06",
                true, "CKSB", "집중·국내·개별주·안정형",
                new PersonalityReportResponse.Shares("0.6364", "0.6364", "0.6364", "0"),
                2, 4,
                List.of(new PersonalityReportResponse.LongHeldStock(
                        "005930", "삼성전자", "KRW", "228000", "241500", "0.0592",
                        OffsetDateTime.parse("2026-08-01T00:00:00Z"))),
                OffsetDateTime.parse("2026-09-09T00:00:00Z"));
    }
}
