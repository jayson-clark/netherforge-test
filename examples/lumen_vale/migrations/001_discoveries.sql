-- The project's own database, `nf.db()`: the places each player has found, and when. The
-- discovery module plays the grove's awakening the first time a player walks into the
-- crystal grove, and this is how it knows it was the first time, across restarts.
CREATE TABLE discoveries (
  player TEXT NOT NULL,
  place TEXT NOT NULL,
  at INTEGER NOT NULL,
  PRIMARY KEY (player, place)
);
