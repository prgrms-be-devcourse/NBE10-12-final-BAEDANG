package com.baedang.market.controller;

import com.baedang.auth.security.JwtAuthenticationFilter;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.global.config.SecurityConfig;
import com.baedang.market.service.DailyCandleSeedService;
import com.baedang.market.service.DailyCandleSeedService.SeedResult;
import com.baedang.stock.entity.MarketCountry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code toss.seed-daily-candles=true} 로 컨트롤러 빈을 등록시켜 트리거 라우팅을 검증한다. */
@WebMvcTest(DailyCandleSeedAdminController.class)
@TestPropertySource(properties = "toss.seed-daily-candles=true")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class DailyCandleSeedAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DailyCandleSeedService dailyCandleSeedService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("market 없이 호출하면 seedAll 을 실행한다")
    void seedAll_whenNoMarket() throws Exception {
        when(dailyCandleSeedService.seedAll()).thenReturn(new SeedResult(3, 2, 1, 0));

        mockMvc.perform(post("/internal/admin/seed/daily-candles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.success").value(2))
                .andExpect(jsonPath("$.failure").value(1))
                .andExpect(jsonPath("$.skipped").value(0));

        verify(dailyCandleSeedService).seedAll();
        verify(dailyCandleSeedService, never()).seed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("market=KR 이면 해당 시장만 시드한다")
    void seedOneMarket_whenMarketGiven() throws Exception {
        when(dailyCandleSeedService.seed(MarketCountry.KR)).thenReturn(new SeedResult(1, 1, 0, 0));

        mockMvc.perform(post("/internal/admin/seed/daily-candles").param("market", "KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(1));

        verify(dailyCandleSeedService).seed(MarketCountry.KR);
        verify(dailyCandleSeedService, never()).seedAll();
    }
}
