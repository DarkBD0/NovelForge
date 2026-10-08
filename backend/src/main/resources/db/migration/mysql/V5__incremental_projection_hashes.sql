ALTER TABLE artifact ADD COLUMN record_hash VARCHAR(64) NULL;
ALTER TABLE artifact_version ADD COLUMN record_hash VARCHAR(64) NULL;
