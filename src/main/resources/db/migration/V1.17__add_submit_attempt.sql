-- Submit lock: set while an ettersendelse is being submitted to Fiks.
-- submit_attempt_id doubles as a fencing token so a replaced attempt cannot finish or clean up.
ALTER TABLE submission
    ADD COLUMN submit_attempt_id uuid,
    ADD COLUMN submit_started_at timestamptz;
