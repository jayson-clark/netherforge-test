-- An instance's anchor: the invisible entity at its anchor that its displays (and the hitboxes sitting at the
-- anchor) ride, so moving it is one teleport. SQLite can't change a CHECK, so the table is made again.
CREATE TABLE instance_entities_new (
  instance TEXT NOT NULL,
  entity TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('display', 'hitbox', 'orphan', 'anchor')),
  node TEXT,
  PRIMARY KEY (instance, entity)
);
INSERT INTO instance_entities_new (instance, entity, role, node) SELECT instance, entity, role, node FROM instance_entities;
DROP TABLE instance_entities;
ALTER TABLE instance_entities_new RENAME TO instance_entities;
