package com.baedang.market.controller;

import com.baedang.market.dto.MarketStatusResponse;
import com.baedang.market.dto.MarketStatusResponse.Market;
import com.baedang.market.service.MarketStatusService;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MarketController.class)
class MarketControllerTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MarketStatusService marketStatusService;

    @Test
    @DisplayName("GET /api/market/status — 200, markets 형태와 값 반환")
    void returnsStatus() throws Exception {
        when(marketStatusService.getStatus()).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/market/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markets[0].marketCountry").value("KR"))
                .andExpect(jsonPath("$.markets[0].open").value(true))
                .andExpect(jsonPath("$.markets[0].opensAt").value("2026-08-11T09:00:00+09:00"))
                .andExpect(jsonPath("$.markets[1].marketCountry").value("US"))
                .andExpect(jsonPath("$.markets[1].open").value(false))
                .andExpect(jsonPath("$.markets[1].nextOpensAt").value("2026-08-11T22:30:00+09:00"));
    }

    @Test
    @DisplayName("null 필드는 명시적 null 키로 나오고(open 라벨 그대로), serverTime은 +09:00")
    void nullKeysExplicitAndKstOffset() throws Exception {
        when(marketStatusService.getStatus()).thenReturn(sampleResponse());

        String body = mockMvc.perform(get("/api/market/status"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // @JsonInclude(ALWAYS): null 이어도 키가 생략되지 않고 명시적 null 로 나와야 한다.
        assertThat(body).contains("\"nextOpensAt\":null"); // KR(개장중)
        assertThat(body).contains("\"opensAt\":null");      // US(개장 전)
        // boolean record 컴포넌트가 "open" 키로 직렬화(-parameters), "isOpen"으로 새지 않음.
        assertThat(body).contains("\"open\":true");
        assertThat(body).contains("\"open\":false");
        assertThat(body).doesNotContain("isOpen");
        // serverTime KST(+09:00).
        assertThat(body).contains("\"serverTime\":\"2026-08-11T12:36:59+09:00\"");
    }

    private static MarketStatusResponse sampleResponse() {
        Market kr = new Market(MarketCountry.KR, true,
                kst(9, 0), kst(15, 30), null);
        Market us = new Market(MarketCountry.US, false,
                null, null, kst(22, 30));
        OffsetDateTime serverTime = OffsetDateTime.of(
                LocalDate.of(2026, 8, 11), LocalTime.of(12, 36, 59), KST);
        return new MarketStatusResponse(List.of(kr, us), serverTime);
    }

    private static OffsetDateTime kst(int h, int m) {
        return OffsetDateTime.of(LocalDate.of(2026, 8, 11), LocalTime.of(h, m), KST);
    }
}
