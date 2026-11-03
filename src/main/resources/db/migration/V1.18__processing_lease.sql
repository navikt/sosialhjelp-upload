-- Processing lease: the worker that claims an upload owns it until processing_started_at + lease.
-- processing_attempt_id fences writes so an expired attempt cannot overwrite a newer one.
ALTER TABLE upload
    ADD COLUMN processing_attempt_id uuid,
    ADD COLUMN processing_started_at timestamptz,
    ADD COLUMN processing_attempts   integer NOT NULL DEFAULT 0;

-- Values: PENDING, READY, PROCESSING, COMPLETE, FAILED
CREATE INDEX upload_work_idx ON upload (processing_status, processing_started_at)
    WHERE processing_status IN ('READY', 'PROCESSING');
