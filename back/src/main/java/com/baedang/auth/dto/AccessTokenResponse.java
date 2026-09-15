package com.baedang.auth.dto;

import java.time.Instant;

/** 서버 간 인증 응답. Vercel 중계는 Refresh를 쿠키로 옮기고 JSON에서 제거합니다. */
public record AccessTokenResponse(String accessToken, String refreshToken, Instant expiresAt) {}
