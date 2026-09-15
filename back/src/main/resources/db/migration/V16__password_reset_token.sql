-- 비밀번호 찾기(이메일 재설정) 토큰.
-- 평문 토큰은 이메일 링크에만 실리고 저장하지 않는다 — DB에는 SHA-256 해시만 남긴다
-- (비밀번호 해시와 같은 이유: 이 테이블이 유출돼도 링크를 재구성할 수 없어야 한다).
CREATE TABLE password_reset_token (
    password_reset_token_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(user_id),
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE password_reset_token IS '비밀번호 찾기 이메일 재설정 토큰(평문 미저장, SHA-256 해시만 보관)';
COMMENT ON COLUMN password_reset_token.token_hash IS '재설정 링크에 실리는 토큰의 SHA-256(hex) 해시';
COMMENT ON COLUMN password_reset_token.used_at IS '재설정에 실제로 사용된 시각. NULL이면 미사용';

-- 새 재설정 요청이 들어오면 그 회원의 이전 미사용 토큰들을 무효화한다(used_at 갱신) —
-- "가장 최근 메일의 링크만 유효"하게 만들어 오래된 링크가 계속 떠도는 것을 막는다.
CREATE INDEX ix_password_reset_token_user_active
    ON password_reset_token (user_id)
    WHERE used_at IS NULL;
