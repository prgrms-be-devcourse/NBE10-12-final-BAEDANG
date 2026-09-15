package com.baedang.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 이메일 링크의 토큰으로 새 비밀번호를 확정하는 요청 — {@code POST /api/auth/password/reset}. */
public record PasswordResetConfirmRequest(
        @NotBlank(message = "재설정 링크가 올바르지 않아요")
        String token,

        @NotBlank(message = "새 비밀번호를 입력해주세요")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상이어야 해요")
        String newPassword
) {}
