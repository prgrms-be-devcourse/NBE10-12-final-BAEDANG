-- 비밀번호 재설정 시 기존 refresh token(stateless JWT, 기본 7일)을 무효화할 수단이
-- 없다는 리뷰 지적(PR #207) — refresh token에 이 값을 그대로 실어뒀다가, 토큰
-- 재발급(POST /api/auth/refresh) 때 회원의 현재 값과 비교한다. 값이 다르면
-- (비밀번호를 재설정해 이 값이 올라간 뒤라면) 재발급을 거부한다.
--
-- access token(기본 15분)까지는 검사하지 않는다 — JwtAuthenticationFilter는 매 요청마다
-- DB를 조회하지 않는 완전한 stateless 필터라, 여기에 버전 검사를 넣으면 앱 전체의
-- 인증된 모든 요청에 DB 조회가 하나씩 추가된다. refresh 시점에는 이미 회원 행을
-- 조회하고 있어(AuthService.refresh) 추가 조회 없이 검사할 수 있다. 그 결과 재설정
-- 직후 최대 access token TTL만큼은 이전 세션이 살아있을 수 있다 — 표준적인 절충이다.
ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0;
COMMENT ON COLUMN users.token_version IS '비밀번호 재설정 등으로 기존 refresh token을 무효화할 때 올린다(User.invalidateSessions)';
