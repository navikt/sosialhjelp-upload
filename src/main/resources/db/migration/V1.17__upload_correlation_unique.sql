CREATE UNIQUE INDEX IF NOT EXISTS upload_submission_correlation_uq
    ON upload (submission_id, correlation_id)
    WHERE correlation_id IS NOT NULL;
