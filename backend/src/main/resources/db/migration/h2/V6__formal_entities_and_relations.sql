ALTER TABLE canon_entity ADD COLUMN entity_key VARCHAR(200);
UPDATE canon_entity SET entity_key=id WHERE entity_key IS NULL;
ALTER TABLE canon_entity ALTER COLUMN entity_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_canon_entity_key ON canon_entity(novel_id,entity_key);

ALTER TABLE entity_relation ADD COLUMN relation_key VARCHAR(200);
UPDATE entity_relation SET relation_key=id WHERE relation_key IS NULL;
ALTER TABLE entity_relation ALTER COLUMN relation_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_entity_relation_key ON entity_relation(novel_id,relation_key);
