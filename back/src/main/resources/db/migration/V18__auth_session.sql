CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id),
    refresh_token_hash VARCHAR(64) NOT NULL,
    refresh_generation BIGINT NOT NULL CHECK (refresh_generation >= 0),
    previous_token_hash VARCHAR(64),
    grace_until TIMESTAMPTZ,
    encrypted_refresh TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_auth_session_user ON auth_session(user_id);
CREATE INDEX idx_auth_session_expiry ON auth_session(expires_at);

-- Stateful 세션으로 대체한 레거시 토큰 버전 컬럼을 제거합니다.
ALTER TABLE users DROP COLUMN token_version;
