ALTER TABLE exchange_rate RENAME COLUMN rate_at TO valid_from;
ALTER TABLE exchange_rate ADD COLUMN valid_until TIMESTAMPTZ;

UPDATE exchange_rate
SET valid_until = valid_from + INTERVAL '1 second'
WHERE valid_until IS NULL;

ALTER TABLE exchange_rate ALTER COLUMN valid_until SET NOT NULL;
ALTER TABLE exchange_rate ADD CONSTRAINT ck_exchange_rate_validity
    CHECK (valid_from < valid_until);
COMMENT ON COLUMN exchange_rate.valid_from IS 'Toss 원본 validFrom(포함). API validFrom과 차트 X축에 사용';
COMMENT ON COLUMN exchange_rate.valid_until IS '유효 종료 시각(미포함)';
