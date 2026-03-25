-- C3: track processing lifecycle to prevent double-processing
-- C4: separate mellomlagring stored size from original Upload-Length
-- L5: indexes for common lookup patterns
ALTER TABLE upload
    ADD COLUMN processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN mellomlagring_storrelse BIGINT;

-- Values: PENDING, PROCESSING, COMPLETE, FAILED
CREATE INDEX idx_upload_document_id ON upload (document_id);
CREATE INDEX idx_document_external_id ON document (external_id);
