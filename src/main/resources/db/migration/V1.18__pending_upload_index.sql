CREATE INDEX upload_work_idx ON upload (updated_at)
    WHERE processing_status = 'PENDING' AND size > 0 AND upload_offset = size;
