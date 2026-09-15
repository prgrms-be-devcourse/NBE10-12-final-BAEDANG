package com.baedang.auth.dto;

import com.baedang.user.entity.User;

/**
 * 회원 프로필 응답.
 *
 * <p>내 정보 조회(GET /api/users/me)와 회원 정보 변경 응답에 사용됩니다.
 * 사용자 식별은 JWT access token의 subject로만 이루어지며,
 * 이 응답의 {@code userId} 를 다른 요청의 식별 수단으로 쓰지 않습니다.
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
