ALTER TABLE upload
    ADD COLUMN gcs_key VARCHAR(500),
    DROP COLUMN chunk_data;
