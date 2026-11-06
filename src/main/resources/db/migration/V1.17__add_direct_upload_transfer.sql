CREATE TABLE direct_upload_transfer (
    operation_id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE RESTRICT,
    context_id TEXT NOT NULL,
    owner_ident VARCHAR(11) NOT NULL,
    calling_application TEXT NOT NULL,
    fiks_digisos_id TEXT,
    kategori TEXT,
    nav_ekstern_ref_id VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    remote_filename VARCHAR(255) NOT NULL,
    content_type TEXT NOT NULL,
    plaintext_size BIGINT NOT NULL,
    encrypted_size BIGINT NOT NULL,
    sha512 VARCHAR(128) NOT NULL,
    payload_sha512 VARCHAR(128) NOT NULL,
    converted BOOLEAN NOT NULL,
    fil_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (nav_ekstern_ref_id, remote_filename)
);

CREATE INDEX direct_upload_transfer_submission_idx ON direct_upload_transfer (submission_id);
CREATE INDEX direct_upload_transfer_owner_ref_idx ON direct_upload_transfer (owner_ident, nav_ekstern_ref_id);
