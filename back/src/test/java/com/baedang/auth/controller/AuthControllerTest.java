package com.baedang.auth.controller;

import com.baedang.auth.dto.AccessTokenResponse;
import com.baedang.auth.service.AuthSessionService;
import com.baedang.auth.dto.AuthResponse;
import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.RefreshTokenRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.auth.security.JwtAuthenticationFilter;
import com.baedang.auth.service.AuthService;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.global.config.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private AuthSessionService sessions;

    @Test
    @DisplayName("회원가입 성공 시 201과 함께 유저, 토큰 정보를 반환")
    void t1() throws Exception {
        SignUpRequest request = new SignUpRequest("user@example.com", "Password123!", "홍길동");
        AuthResponse response = new AuthResponse(
                1L,
                "user@example.com",
                "홍길동",
                "access-token",
                "refresh-token",
                new AuthResponse.AccountInfo(10L, 1, "50000000", "50000000"), Instant.parse("2026-09-21T00:00:00Z")
        );

        when(authService.signUp(any())).thenReturn(response);

        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.nickname").value("홍길동"))
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.account.accountId").value(10))
                .andExpect(jsonPath("$.account.initialCash").value("50000000"));
    }

    @Test
    @DisplayName("로그인 성공 시 200과 함께 유저, 토큰 정보를 반환")
    void t2() throws Exception {
        LoginRequest request = new LoginRequest("user@example.com","Password123!");
        AuthResponse response = new AuthResponse(
                1L,
                "user@example.com",
                "홍길동",
                "access-token",
                "refresh-token",
                new AuthResponse.AccountInfo(10L, 1, "50000000", "50000000"), Instant.parse("2026-09-21T00:00:00Z")
        );
        when(authService.login(any())).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.nickname").value("홍길동"))
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.account.accountId").value(10))
                .andExpect(jsonPath("$.account.initialCash").value("50000000"));
    }

    @Test
    @DisplayName("refresh token으로 새 access token을 발급한다")
    void refresh_token으로_새_access_token을_발급한다() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("refresh-token");
        when(authService.refresh(request))
                .thenReturn(new AccessTokenResponse("new-access", "new-refresh", Instant.parse("2026-09-21T00:00:00Z")));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"));
    }

    @Test
    @DisplayName("refresh token이 비어 있으면 INVALID_INPUT을 반환한다")
    void refresh_token이_비어_있으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("Refresh로 현재 세션을 로그아웃하고 빈 응답을 받는다")
    void logout_with_refresh() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
        verify(authService).logout(new RefreshTokenRequest("refresh-token"));
    }

    @Test
    @DisplayName("로그아웃에도 Refresh 입력 검증을 적용한다")
    void logout_requires_refresh() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
