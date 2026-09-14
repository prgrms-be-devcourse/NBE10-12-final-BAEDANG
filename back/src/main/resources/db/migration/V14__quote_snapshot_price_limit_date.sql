-- 기존 값과 시세 이력은 보존하며 날짜 없는 상하한가는 미검증 상태로 취급합니다.
ALTER TABLE quote_snapshot ADD COLUMN price_limit_date DATE;
COMMENT ON COLUMN quote_snapshot.price_limit_date IS '국내 상하한가 데이터의 거래소 현지 적용일. 미국 및 미검증 값은 NULL';
