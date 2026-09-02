package com.baedang.auth.controller;

import com.baedang.auth.dto.AuthResponse;
import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.dto.UserResponse;
import com.baedang.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 회원가입 · 로그인.
 *
 * <p><b>1주차 범위</b> — 비밀번호 해시 저장과 검증까지만 합니다.
 * 토큰도 세션도 발급하지 않습니다.
 *
 * <p>이후 요청은 {@code X-User-Id} 헤더로 사용자를 식별합니다.
 * <b>개발용이라 배포하면 안 됩니다</b> — 헤더를 바꾸면 남의 계좌가 열립니다.
 * 2주차에 JWT 로 교체하면서 이 헤더를 없애세요.
 */
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

    /**
     * 내 정보. 1주차에는 헤더에서 userId 를 그대로 받습니다.
     *
     * <p>2주차에는 이 파라미터가 사라지고 토큰에서 꺼내게 됩니다.
     * 그때 컨트롤러 시그니처가 바뀌므로, 서비스 계층은 지금부터
     * {@code Long userId} 만 받도록 해뒀습니다 — 서비스는 안 고쳐도 됩니다.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(authService.getMe(userId));
    }
}
