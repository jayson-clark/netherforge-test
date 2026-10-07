-- The library's own database, `nf.db()` in its scripts: a ledger of every quest a player
-- started and finished, with when. What a player is doing now lives in their saved table;
-- this is the history the journal reads back, and what the server has seen done.
CREATE TABLE quest_log (
  id INTEGER PRIMARY KEY,
  player TEXT NOT NULL,
  quest TEXT NOT NULL,
  event TEXT NOT NULL,
  at INTEGER NOT NULL
);

CREATE INDEX quest_log_player ON quest_log (player, at);
