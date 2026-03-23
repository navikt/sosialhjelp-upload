ALTER TABLE upload
    ADD COLUMN upload_offset BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN chunk_data    BYTEA,
    ADD COLUMN fil_id        UUID,
    ADD COLUMN mellomlagring_ref_id VARCHAR(255);

ALTER TABLE upload
    DROP COLUMN signed_url,
    DROP COLUMN converted_filename;
