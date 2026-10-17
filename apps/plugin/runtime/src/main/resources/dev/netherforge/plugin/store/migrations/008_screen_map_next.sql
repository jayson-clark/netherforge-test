-- The next map number screens may take: past every one ever given, kept ones or not, so a number is never given
-- twice, even after a keyed screen gives its numbers up (screen:remove()) or a restart. One row.
CREATE TABLE screen_map_next (
  id INTEGER PRIMARY KEY CHECK (id = 0),
  next INTEGER NOT NULL
);
