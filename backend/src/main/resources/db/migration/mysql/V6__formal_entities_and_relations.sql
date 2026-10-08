ALTER TABLE canon_entity ADD COLUMN entity_key VARCHAR(200) NULL AFTER novel_id;
UPDATE canon_entity SET entity_key=id WHERE entity_key IS NULL;
ALTER TABLE canon_entity MODIFY COLUMN entity_key VARCHAR(200) NOT NULL;
CREATE UNIQUE INDEX uq_canon_entity_key ON canon_entity(novel_id,entity_key);

ALTER TABLE entity_relation ADD COLUMN relation_key VARCHAR(200) NULL AFTER novel_id;
UPDATE entity_relation SET relation_key=id WHERE relation_key IS NULL;
ALTER TABLE entity_relation MODIFY COLUMN relation_key VARCHAR(200) NOT NULL;
CREATE UNIQUE INDEX uq_entity_relation_key ON entity_relation(novel_id,relation_key);
