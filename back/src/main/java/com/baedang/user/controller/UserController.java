package com.baedang.user.controller;

import com.baedang.auth.dto.UserResponse;
import com.baedang.user.dto.ChangePasswordRequest;
import com.baedang.user.dto.UpdateNicknameRequest;
import com.baedang.user.dto.WithdrawRequest;
import com.baedang.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 회원 생명주기 API. 사용자 식별은 JWT subject의 userId만 신뢰합니다.
 *
 * <p>userId를 header·path·query·body로 받지 않습니다 — 받으면 위조가 가능합니다.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(userService.getMe(userId));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> changeNickname(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateNicknameRequest request
    ) {
        return ResponseEntity.ok(userService.changeNickname(userId, request));
    }

    @PutMapping("/me/password")
    public ResponseEntity<UserResponse> changePassword(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        return ResponseEntity.ok(userService.changePassword(userId, request));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody WithdrawRequest request
    ) {
        userService.withdraw(userId, request);
        return ResponseEntity.ok().build();
    }
}
