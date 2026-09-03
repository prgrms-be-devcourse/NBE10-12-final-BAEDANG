package com.baedang.auth.security;

import com.baedang.global.error.ErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final RestAuthenticationEntryPoint entryPoint;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, RestAuthenticationEntryPoint entryPoint) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request,response);
            return;
        }

        if(SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request,response);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();

        Long userId;
        try {
            userId = jwtTokenProvider.parseAccessToken(token);
        } catch (ExpiredJwtException e) {
            SecurityContextHolder.clearContext();
            entryPoint.write(response, ErrorCode.TOKEN_EXPIRED);
            return;
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            entryPoint.write(response, ErrorCode.INVALID_TOKEN);
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
