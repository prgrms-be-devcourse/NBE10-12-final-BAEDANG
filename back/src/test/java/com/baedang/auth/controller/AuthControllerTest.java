package com.baedang.auth.controller;

import com.baedang.auth.dto.AuthResponse;
import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.auth.service.AuthService;
import com.baedang.auth.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RestAuthenticationEntryPoint restAuthenticationEntryPoint;

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
}
