-- Preserve existing prices and history. A null date keeps legacy references unverified.
ALTER TABLE quote_snapshot ADD COLUMN prev_close_date DATE;
