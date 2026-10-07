-- The runtime's own state. Every row a project's package persists says whose it is: a
-- namespace column, or a name written in full (`shop:turret`).

-- Spawned centities: which exist, what they are, where they're anchored and which way they
-- face. The anchor and facing are written when the world saves, the chunk unloads or the
-- server stops (as the entities themselves are), not every time they change.
CREATE TABLE instances (
  id TEXT NOT NULL PRIMARY KEY,
  centity TEXT NOT NULL,
  world TEXT NOT NULL,
  x REAL NOT NULL,
  y REAL NOT NULL,
  z REAL NOT NULL,
  yaw REAL NOT NULL DEFAULT 0
);

-- Each instance's entities: a display or hitbox per node, and orphans still to remove.
CREATE TABLE instance_entities (
  instance TEXT NOT NULL,
  entity TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('display', 'hitbox', 'orphan')),
  node TEXT,
  PRIMARY KEY (instance, entity)
);

-- Saved tables, each the prelude's JSON encoding: centity:data(), player:data(), and each
-- package's nf.data(name).
CREATE TABLE centity_data (
  instance TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE player_data (
  player TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE named_data (
  namespace TEXT NOT NULL,
  name TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (namespace, name)
);

-- The permission nodes a project's scripts set on players.
CREATE TABLE permissions (
  namespace TEXT NOT NULL,
  player TEXT NOT NULL,
  node TEXT NOT NULL,
  value INTEGER NOT NULL CHECK (value IN (0, 1)),
  PRIMARY KEY (namespace, player, node)
);

-- The worlds a project's scripts created, which it may unload and delete.
CREATE TABLE worlds (
  world TEXT NOT NULL PRIMARY KEY,
  namespace TEXT NOT NULL,
  environment TEXT NOT NULL
);
