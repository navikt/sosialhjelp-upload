ALTER TABLE document RENAME TO submission;
ALTER TABLE upload RENAME COLUMN document_id TO submission_id;
