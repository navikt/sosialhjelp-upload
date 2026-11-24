-- Earlier TUS creates had no conflict handling; retries produced duplicate rows.
-- Keep one row per group (COMPLETE first, then the most recent) and set correlation_id to NULL on the rest.
WITH ranked AS (
    SELECT id, row_number() OVER (
        PARTITION BY submission_id, correlation_id
        ORDER BY (processing_status = 'COMPLETE') DESC, updated_at DESC, id
    ) AS rn
    FROM upload
    WHERE correlation_id IS NOT NULL
)
UPDATE upload SET correlation_id = NULL
WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

CREATE UNIQUE INDEX IF NOT EXISTS upload_submission_correlation_uq
    ON upload (submission_id, correlation_id)
    WHERE correlation_id IS NOT NULL;
