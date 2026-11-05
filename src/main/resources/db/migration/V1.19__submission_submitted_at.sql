-- Submitted submissions are kept (without uploads) so their navEksternRefId still counts
-- when the next ref for the same fiksDigisosId is derived. Deleted after a retention period.
ALTER TABLE submission ADD COLUMN submitted_at timestamptz;
