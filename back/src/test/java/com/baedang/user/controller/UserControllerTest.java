package com.baedang.user.controller;

import com.baedang.auth.dto.UserResponse;
import com.baedang.auth.security.JwtAuthenticationFilter;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.auth.security.RestAuthenticationEntryPoint;
import com.baedang.global.config.SecurityConfig;
import com.baedang.user.dto.ChangePasswordRequest;
import com.baedang.user.dto.UpdateNicknameRequest;
import com.baedang.user.dto.WithdrawRequest;
import com.baedang.user.service.UserService;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("내 정보는 token principal의 user를 조회한다")
    void 내_정보는_token_principal의_user를_조회한다() throws Exception {
        when(userService.getMe(7L))
                .thenReturn(new UserResponse(7L, "user@example.com", "테스터"));

        mockMvc.perform(get("/api/users/me")
                        .with(authenticatedUser(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.nickname").value("테스터"));

        verify(userService).getMe(7L);
    }

    @Test
    @DisplayName("nickname을 변경하고 갱신된 user를 응답한다")
    void nickname을_변경하고_갱신된_user를_응답한다() throws Exception {
        UpdateNicknameRequest request = new UpdateNicknameRequest("새닉네임");
        when(userService.changeNickname(7L, request))
                .thenReturn(new UserResponse(7L, "user@example.com", "새닉네임"));

        mockMvc.perform(patch("/api/users/me")
                        .with(authenticatedUser(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"));

        verify(userService).changeNickname(7L, new UpdateNicknameRequest("새닉네임"));
    }

    @Test
    @DisplayName("password를 변경하고 passwordHash는 응답하지 않는다")
    void password를_변경하고_passwordHash는_응답하지_않는다() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("current-password", "new-password");
        when(userService.changePassword(7L, request))
                .thenReturn(new UserResponse(7L, "user@example.com", "테스터"));

        mockMvc.perform(put("/api/users/me/password")
                        .with(authenticatedUser(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        verify(userService).changePassword(7L,
                new ChangePasswordRequest("current-password", "new-password"));
    }

    @Test
    @DisplayName("현재 password를 확인하고 탈퇴한다")
    void 현재_password를_확인하고_탈퇴한다() throws Exception {
        WithdrawRequest request = new WithdrawRequest("current-password");

        mockMvc.perform(delete("/api/users/me")
                        .with(authenticatedUser(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(userService).withdraw(7L, new WithdrawRequest("current-password"));
    }

    @Test
    @DisplayName("인증이 없으면 네 endpoint가 모두 401이다")
    void 인증이_없으면_네_endpoint가_모두_401이다() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(patch("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(put("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(delete("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("nickname과 password validation 오류는 INVALID_INPUT이다")
    void nickname과_password_validation_오류는_INVALID_INPUT이다() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .with(authenticatedUser(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"짧\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        mockMvc.perform(put("/api/users/me/password")
                        .with(authenticatedUser(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private static RequestPostProcessor authenticatedUser(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of()));
    }
}
