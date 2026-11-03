-- Uploads that were PROCESSING under the old code have no lease (processing_started_at IS NULL),
-- so neither the worker nor recovery would ever pick them up again. Put them back in the queue.
UPDATE upload
SET processing_status = 'READY'
WHERE processing_status = 'PROCESSING'
  AND processing_started_at IS NULL;
