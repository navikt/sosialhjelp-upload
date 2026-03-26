ALTER TABLE upload ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX upload_recovery_idx ON upload (processing_status, updated_at)
    WHERE processing_status IN ('PROCESSING', 'PENDING');
