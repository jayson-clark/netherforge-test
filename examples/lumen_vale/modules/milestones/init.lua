-- Milestones: the project's advancements (advancements/*.json), a tree hung under the vale_lore
-- library's "Vale Chronicle" tab. Each is granted here, from the custom events the rest of the
-- project raises, so the scripts that do things don't need to know about advancements.
local keeper = require("keeper")

-- Event → the advancement it completes.
local GRANTS = {
  ["lumen_vale:shard_found"] = "first_light",
  ["lumen_vale:infused"] = "altar_tender",
  ["lumen_vale:ascended"] = "above_the_vale",
}

for event, advancement in pairs(GRANTS) do
  nf.on(event, function(raised)
    raised.player:grant_advancement(advancement)
  end)
end

-- The keeper's quest is the library's to complete; it says so with its own event.
nf.on("vale_lore:quest_completed", function(event)
  if event.quest == keeper.QUEST then
    event.player:grant_advancement("keepers_friend")
  end
end)
