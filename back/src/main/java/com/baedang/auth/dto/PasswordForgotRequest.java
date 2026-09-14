package com.baedang.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 비밀번호 찾기 메일 발송 요청 — {@code POST /api/auth/password/forgot}. */
public record PasswordResetRequestRequest(
        @NotBlank(message = "이메일을 입력해주세요")
        @Email(message = "이메일 형식이 올바르지 않아요")
        @Size(max = 255)
        String email
) {}
