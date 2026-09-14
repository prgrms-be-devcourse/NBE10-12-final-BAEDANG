package com.baedang.auth.controller;

import com.baedang.auth.dto.AccessTokenResponse;
import com.baedang.auth.dto.AuthResponse;
import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.PasswordResetConfirmRequest;
import com.baedang.auth.dto.PasswordResetRequestRequest;
import com.baedang.auth.dto.RefreshTokenRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 회원가입, 로그인, 토큰 재발급 및 stateless 로그아웃 API. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 201 Created. 가입과 동시에 1회차 계좌가 만들어집니다. */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signUp(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok().build();
    }

    /**
     * 비밀번호 찾기 메일 발송. 가입 여부와 무관하게 항상 200을 돌려준다 — 계정 열거
     * 공격 방지(AuthService.requestPasswordReset 문서 참고).
     */
    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody PasswordResetRequestRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.ok().build();
    }

    /** 이메일 링크의 토큰으로 새 비밀번호를 확정한다. */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok().build();
    }

}
