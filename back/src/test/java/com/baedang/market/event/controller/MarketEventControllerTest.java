package com.baedang.market.event.controller;

import com.baedang.auth.security.JwtAuthenticationFilter;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.global.config.SecurityConfig;
import com.baedang.market.event.dto.MarketEventListResponse;
import com.baedang.market.event.entity.KrMarket;
import com.baedang.market.event.entity.MarketEventType;
import com.baedang.market.event.entity.SidecarDirection;
import com.baedang.market.event.service.MarketEventQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이 조회는 공개다 — 인증 없이 접근할 수 있어야 한다. 시장과 날짜는 enum 바인딩에 맡기지 않고
 * 직접 파싱해, 프레임워크 기본 메시지가 아니라 다른 API와 같은 {@code INVALID_INPUT} 계약을 낸다.
 */
@WebMvcTest(MarketEventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class MarketEventControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MarketEventQueryService queryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void events_endpoint_is_public_and_returns_structured_items() throws Exception {
        when(queryService.get(any(), any())).thenReturn(sample(KrMarket.KOSPI));

        mvc.perform(get("/api/market/events")
                        .param("market", "KOSPI")
                        .param("date", "2026-07-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("KOSPI"))
                .andExpect(jsonPath("$.date").value("2026-07-13"))
                .andExpect(jsonPath("$.items[0].eventType").value("CIRCUIT_BREAKER"))
                .andExpect(jsonPath("$.items[0].stage").value(1))
                .andExpect(jsonPath("$.items[0].triggeredAt").value("2026-07-13T13:28:32+09:00"))
                .andExpect(jsonPath("$.items[0].haltUntil").value("2026-07-13T13:48:32+09:00"));
    }

    @Test
    void events_endpoint_requires_no_authentication() throws Exception {
        when(queryService.get(any(), any())).thenReturn(sample(KrMarket.KOSPI));

        mvc.perform(get("/api/market/events")
                        .param("market", "KOSPI")
                        .param("date", "2026-07-13"))
                .andExpect(status().isOk());
    }

    @Test
    void sidecar_items_expose_direction_and_no_stage() throws Exception {
        when(queryService.get(any(), any())).thenReturn(new MarketEventListResponse(
                KrMarket.KOSPI,
                LocalDate.of(2026, 7, 13),
                List.of(new MarketEventListResponse.Item(
                        2L,
                        MarketEventType.SIDECAR,
                        null,
                        SidecarDirection.BUY,
                        OffsetDateTime.parse("2026-07-13T09:06:41+09:00"),
                        OffsetDateTime.parse("2026-07-13T09:11:41+09:00"),
                        OffsetDateTime.parse("2026-07-13T09:07:00+09:00"),
                        OffsetDateTime.parse("2026-07-13T09:07:05+09:00"),
                        true,
                        "유가증권시장 매수 사이드카(Side car) 발동",
                        URI.create("https://kind.krx.co.kr/external/2026/07/15/000066/20260715000125/99404.htm")))));

        mvc.perform(get("/api/market/events")
                        .param("market", "KOSPI")
                        .param("date", "2026-07-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("SIDECAR"))
                .andExpect(jsonPath("$.items[0].direction").value("BUY"))
                .andExpect(jsonPath("$.items[0].stage").doesNotExist());
    }

    @Test
    void missing_market_is_invalid_input_with_field() throws Exception {
        mvc.perform(get("/api/market/events").param("date", "2026-07-13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data.field").value("market"));

        verify(queryService, never()).get(any(), any());
    }

    @Test
    void invalid_market_is_invalid_input_with_field() throws Exception {
        mvc.perform(get("/api/market/events")
                        .param("market", "NYSE")
                        .param("date", "2026-07-13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data.field").value("market"));

        verify(queryService, never()).get(any(), any());
    }

    @Test
    void missing_date_is_invalid_input_with_field() throws Exception {
        mvc.perform(get("/api/market/events").param("market", "KOSPI"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data.field").value("date"));
    }

    @Test
    void invalid_date_is_invalid_input_with_field() throws Exception {
        for (String invalid : List.of("2026-13-40", "20260713", "yesterday", "2026-02-30")) {
            mvc.perform(get("/api/market/events")
                            .param("market", "KOSPI")
                            .param("date", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.data.field").value("date"));
        }

        verify(queryService, never()).get(any(), any());
    }

    @Test
    void empty_history_returns_empty_items() throws Exception {
        when(queryService.get(any(), any())).thenReturn(new MarketEventListResponse(
                KrMarket.KOSPI, LocalDate.of(2026, 7, 13), List.of()));

        mvc.perform(get("/api/market/events")
                        .param("market", "KOSPI")
                        .param("date", "2026-07-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    /** 조회 응답에는 주문 거절의 retryPolicy가 없어야 한다. 그건 Part 4의 주문 오류 계약이다. */
    @Test
    void response_has_no_retry_policy() throws Exception {
        when(queryService.get(any(), any())).thenReturn(sample(KrMarket.KOSPI));

        String body = mvc.perform(get("/api/market/events")
                        .param("market", "KOSPI")
                        .param("date", "2026-07-13"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("retryPolicy");
    }

    @Test
    void lowercase_market_is_accepted() throws Exception {
        when(queryService.get(any(), any())).thenReturn(sample(KrMarket.KOSPI));

        mvc.perform(get("/api/market/events")
                        .param("market", "kospi")
                        .param("date", "2026-07-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("KOSPI"));
    }

    private static MarketEventListResponse sample(KrMarket market) {
        return new MarketEventListResponse(
                market,
                LocalDate.of(2026, 7, 13),
                List.of(new MarketEventListResponse.Item(
                        1L,
                        MarketEventType.CIRCUIT_BREAKER,
                        1,
                        null,
                        OffsetDateTime.parse("2026-07-13T13:28:32+09:00"),
                        OffsetDateTime.parse("2026-07-13T13:48:32+09:00"),
                        OffsetDateTime.parse("2026-07-13T13:29:00+09:00"),
                        OffsetDateTime.parse("2026-07-13T13:29:07+09:00"),
                        false,
                        "유가증권시장 매매거래 일시중단(1단계 CB 발동)",
                        URI.create("https://kind.krx.co.kr/external/2026/07/13/000273/20260713000658/99443.htm"))));
    }
}
