local this = this --[[@as ProjectItem]]

-- Any lumen shard a player picks up, from ore, a shrine chest or a fed tortoise. The milestones
-- module turns the first one into the "First Light" advancement.
this:on("pickup", function(event)
  nf.emit("lumen_vale:shard_found", { player = event.player, count = event.item.count })
end)
