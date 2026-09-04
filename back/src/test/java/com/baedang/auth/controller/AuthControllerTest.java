package com.baedang.auth.controller;

import com.baedang.auth.dto.AccessTokenResponse;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
                new AuthResponse.AccountInfo(10L, 1, "50000000", "50000000")
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
                new AuthResponse.AccountInfo(10L, 1, "50000000", "50000000")
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
                .thenReturn(new AccessTokenResponse("new-access"));

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
    @DisplayName("인증된 사용자는 stateless logout 시 200과 빈 body를 받는다")
    void 인증된_사용자는_logout할_수_있다() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .with(authenticatedUser(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("인증 없이 logout하면 UNAUTHORIZED를 반환한다")
    void 인증_없이_logout하면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private static RequestPostProcessor authenticatedUser(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of()));
    }
}
