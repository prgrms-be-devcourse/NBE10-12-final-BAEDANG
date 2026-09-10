-- rate_at은 기존부터 원본 validFrom입니다. 과거 validUntil은 복원할 수 없으므로 추정하지 않습니다.
-- 기존 행은 화면/이력에 남기고, 원본 유효기간이 없는 행은 체결에서 거절합니다.
ALTER TABLE exchange_rate RENAME COLUMN rate_at TO valid_from;
ALTER TABLE exchange_rate ADD COLUMN valid_until TIMESTAMPTZ;
ALTER TABLE exchange_rate ADD CONSTRAINT ck_exchange_rate_validity
    CHECK (valid_until IS NULL OR valid_from < valid_until);
-- 과거 행을 고쳐 만들지 않되, 이번 변경 이후 INSERT/UPDATE에는 원본 종료 시각을 필수로 요구합니다.
ALTER TABLE exchange_rate ADD CONSTRAINT ck_exchange_rate_valid_until_required
    CHECK (valid_until IS NOT NULL) NOT VALID;
COMMENT ON COLUMN exchange_rate.valid_from IS 'Toss 원본 validFrom(포함). API validFrom과 차트 X축에 사용';
COMMENT ON COLUMN exchange_rate.valid_until IS 'Toss 원본 validUntil(미포함). NULL인 과거 이력은 체결에 사용하지 않음';
