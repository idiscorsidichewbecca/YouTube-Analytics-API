ALTER TABLE short_publication_drafts ADD COLUMN batch_id VARCHAR(36);
ALTER TABLE short_publication_drafts ADD COLUMN batch_position INTEGER;
ALTER TABLE short_publication_drafts ADD COLUMN batch_filename VARCHAR(255);
ALTER TABLE short_publication_drafts ADD COLUMN batch_file_id VARCHAR(133);
CREATE UNIQUE INDEX ux_short_batch_position ON short_publication_drafts (batch_id, batch_position);
CREATE UNIQUE INDEX ux_short_batch_file ON short_publication_drafts (batch_id, batch_file_id);
