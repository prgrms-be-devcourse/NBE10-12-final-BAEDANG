package com.baedang.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청.
 *
 * <p>검증 실패는 {@code GlobalExceptionHandler} 가 INVALID_INPUT 으로 바꿔주고,
 * 어느 필드가 왜 틀렸는지 {@code data} 에 담아 내려줍니다.
 */
public record SignUpRequest(

        @NotBlank(message = "이메일을 입력해주세요")
        @Email(message = "이메일 형식이 올바르지 않아요")
        @Size(max = 255)
        String email,

        /*
         * 8자 이상만 봅니다. 특수문자 강제 같은 규칙은 넣지 않았습니다 —
         * 모의투자 서비스라 과하고, 실제로 보안에 크게 기여하지도 않습니다.
         * 필요하면 팀에서 정해서 @Pattern 을 추가하세요.
         */
        @NotBlank(message = "비밀번호를 입력해주세요")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상이어야 해요")
        String password,

        @NotBlank(message = "닉네임을 입력해주세요")
        @Size(min = 2, max = 20, message = "닉네임은 2~20자로 입력해주세요")
        String nickname
) {}
