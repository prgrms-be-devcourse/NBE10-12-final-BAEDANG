package com.baedang.auth.dto;

import com.baedang.user.entity.User;

/**
 * 회원가입·로그인 성공 응답.
 *
 * <p><b>토큰이 없습니다.</b> 1주차에는 인증을 붙이지 않기로 했으므로
 * 로그인은 "비밀번호가 맞는지 확인해주는 API" 에 가깝습니다.
 * 프론트는 이 {@code userId} 를 들고 다니며 이후 요청의
 * {@code X-User-Id} 헤더에 실어 보냅니다.
 *
 * <p><b>이 방식은 개발용입니다.</b> 헤더를 아무 값으로나 바꾸면 남의 계좌가
 * 열리므로 절대 배포하면 안 됩니다. 2주차에 JWT 로 교체하면서
 * 이 응답에 {@code accessToken} 을 추가하게 됩니다.
 *
 * <p>{@code passwordHash} 는 <b>절대 넣지 마세요.</b> 엔티티를 그대로
 * 반환하지 않고 이 DTO 를 거치는 이유가 그것입니다.
 */
public record UserResponse(
        Long userId,
        String email,
        String nickname
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getUserId(), user.getEmail(), user.getNickname());
    }
}
