-- The numbers a screen's maps keep for good: a project screen's, and those of a script's screen made with a key, so
-- a map item or a frame of one still shows it after a restart. One row a map; a number is never given twice.
CREATE TABLE screen_maps (
  namespace TEXT NOT NULL,
  screen TEXT NOT NULL,
  tile INTEGER NOT NULL,
  map INTEGER NOT NULL UNIQUE,
  PRIMARY KEY (namespace, screen, tile)
);
