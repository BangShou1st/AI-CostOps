-- M2 Group 1 review fix: provider usage windows must be well-formed.
-- Forward-only migration; V4 is not rewritten.

ALTER TABLE raw_provider_record
ADD CONSTRAINT chk_raw_provider_record_usage_window
CHECK (usage_start IS NULL OR usage_end IS NULL OR usage_start <= usage_end);
