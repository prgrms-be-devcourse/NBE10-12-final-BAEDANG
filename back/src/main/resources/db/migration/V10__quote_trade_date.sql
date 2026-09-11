-- 기존 가격과 이력을 보존한다. 날짜가 null인 기존 기준가는 미검증 상태로 유지한다.
ALTER TABLE quote_snapshot ADD COLUMN prev_close_date DATE;
