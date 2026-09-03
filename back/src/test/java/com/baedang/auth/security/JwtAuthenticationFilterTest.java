package com.baedang.auth.security;

import com.baedang.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private JwtTokenProvider jwtTokenProvider;
    private RestAuthenticationEntryPoint entryPoint;
    private JwtAuthenticationFilter filter;
    private FilterChain chain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        entryPoint = new RestAuthenticationEntryPoint(objectMapper);
        filter = new JwtAuthenticationFilter(jwtTokenProvider,entryPoint);
        chain = mock(FilterChain.class);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Bearer access token의 userId를 principal로 등록")
    void t1() throws ServletException, IOException {
        when(jwtTokenProvider.parseAccessToken("valid-token")).thenReturn(7L);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");

        filter.doFilter(request,response,chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(7L);
        assertThat(authentication.getCredentials()).isNull();
        verify(chain).doFilter(request,response);
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 context를 만들지 않고 chain을 계속")
    void t2() throws ServletException, IOException {
        filter.doFilter(request,response,chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request,response);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    @DisplayName("만료된 token은 TOKEN_EXPIRED 401을 기록하고 chain을 중단")
    void t3() throws ServletException, IOException {
        when(jwtTokenProvider.parseAccessToken("expired-token"))
                .thenThrow(new ExpiredJwtException(null,null,"expired"));
        request.addHeader(HttpHeaders.AUTHORIZATION,"Bearer expired-token");

        filter.doFilter(request,response,chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(ErrorCode.TOKEN_EXPIRED.name());
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("변조된 token은 INVALID_TOKEN 401을 기록하고 chain을 중단")
    void t4() throws ServletException, IOException {
        when(jwtTokenProvider.parseAccessToken("invalid-token"))
                .thenThrow(new JwtException("invalid signature"));
        request.addHeader(HttpHeaders.AUTHORIZATION,"Bearer invalid-token");

        filter.doFilter(request,response,chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(ErrorCode.INVALID_TOKEN.name());
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("이미 인증된 context가 있으면 덮어쓰지 않고 chain을 계속")
    void t5() throws ServletException, IOException {
        Authentication existing = new UsernamePasswordAuthenticationToken(99L,null, List.of());
        SecurityContextHolder.getContext().setAuthentication(existing);
        request.addHeader(HttpHeaders.AUTHORIZATION,"Bearer any-token");

        filter.doFilter(request,response,chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(99L);
        verify(chain).doFilter(request,response);
        verifyNoInteractions(jwtTokenProvider);
    }


    @Test
    @DisplayName("인증 이후 downstream 예외를 token 오류로 변환하지 않는다")
    void downstream_예외는_그대로_전파한다() throws Exception {
        when(jwtTokenProvider.parseAccessToken("valid-token")).thenReturn(7L);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        IllegalArgumentException downstreamFailure = new IllegalArgumentException("domain failure");
        doThrow(downstreamFailure).when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isSameAs(downstreamFailure);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
