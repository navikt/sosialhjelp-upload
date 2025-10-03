CREATE TABLE IF NOT EXISTS document
(
    id uuid PRIMARY KEY,
    external_id VARCHAR(255) NOT NULL UNIQUE,
    owner_ident char(11)     NOT NULL
);

CREATE TABLE IF NOT EXISTS upload (
    id          uuid PRIMARY KEY,
    document_id uuid references document(id) ON DELETE CASCADE,
    original_filename varchar(255) NOT NULL,
    signed_url varchar,
    converted_filename varchar(255),
    size bigint
);

CREATE TABLE IF NOT EXISTS error
(
    id     uuid PRIMARY KEY,
    upload uuid references upload(id) ON DELETE CASCADE,
    code   text NOT NULL
);
