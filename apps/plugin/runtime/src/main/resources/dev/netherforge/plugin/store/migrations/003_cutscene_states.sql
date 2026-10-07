-- What a player was before the cutscene they're watching started (one row each, gone when
-- they're put back), so a crash mid-cutscene can still put them back when they next join.
CREATE TABLE cutscene_states (
  player TEXT PRIMARY KEY,
  world TEXT NOT NULL,
  x REAL NOT NULL,
  y REAL NOT NULL,
  z REAL NOT NULL,
  yaw REAL NOT NULL,
  pitch REAL NOT NULL,
  game_mode TEXT NOT NULL,
  can_fly INTEGER NOT NULL,
  flying INTEGER NOT NULL,
  spectating TEXT
);
