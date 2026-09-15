package com.baedang.auth.security;

import com.baedang.global.error.ErrorCode;
import com.baedang.global.error.BusinessException;
import com.baedang.auth.service.AuthSessionService;
import com.baedang.auth.security.JwtTokenProvider.Identity;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
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
    private final AuthSessionService sessions;
    private final RestAuthenticationEntryPoint entryPoint;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, RestAuthenticationEntryPoint entryPoint, AuthSessionService sessions) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.sessions = sessions;
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
            Identity identity = jwtTokenProvider.accessIdentity(token);
            sessions.requireActive(identity);
            userId = identity.userId();
        } catch (BusinessException exception) {
            SecurityContextHolder.clearContext();
            entryPoint.write(response, exception.getErrorCode());
            return;
        } catch (DataAccessException | TransactionException exception) {
            SecurityContextHolder.clearContext();
            entryPoint.write(response, ErrorCode.AUTH_UNAVAILABLE);
            return;
        } catch (ExpiredJwtException e) {
            SecurityContextHolder.clearContext();
            entryPoint.write(response, ErrorCode.TOKEN_EXPIRED);
            return;
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            entryPoint.write(response, ErrorCode.INVALID_TOKEN);
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
