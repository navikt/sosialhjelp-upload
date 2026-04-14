ALTER TABLE submission RENAME COLUMN external_id TO context_id;
ALTER TABLE submission ADD COLUMN nav_ekstern_ref_id VARCHAR(255);
UPDATE submission SET nav_ekstern_ref_id = id::text;
ALTER TABLE submission ALTER COLUMN nav_ekstern_ref_id SET NOT NULL;
ALTER TABLE upload DROP COLUMN mellomlagring_ref_id;
