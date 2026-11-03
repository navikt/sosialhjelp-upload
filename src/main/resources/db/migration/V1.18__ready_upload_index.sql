CREATE INDEX upload_work_idx ON upload (updated_at)
    WHERE processing_status = 'READY';
