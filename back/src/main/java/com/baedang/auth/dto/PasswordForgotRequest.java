package com.baedang.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 찾기 메일 발송 요청 — {@code POST /api/auth/password/forgot}.
 *
 * <p>원래 이름 {@code PasswordResetRequestRequest}는 "Reset Request"(비밀번호 재설정
 * 요청이라는 도메인 의미)와 DTO 접미사 "Request"가 겹쳐 어색하다는 리뷰 지적
 * (PR #207) — 컨트롤러 메서드/엔드포인트 이름(forgotPassword, {@code /password/forgot})과
 * 맞춰 이름 붙였습니다.
 */
public record PasswordForgotRequest(
        @NotBlank(message = "이메일을 입력해주세요")
        @Email(message = "이메일 형식이 올바르지 않아요")
        @Size(max = 255)
        String email
) {}
