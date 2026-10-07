local this = this --[[@as ProjectItem]]

-- The journal's one script. Any journal, whichever project's script handed it over, opens the
-- library's journal dialog with the holder's quests in it.
local quests = require("quests")

this:on("use", function(event)
  event:cancel()
  quests.open_journal(event.player)
end)
