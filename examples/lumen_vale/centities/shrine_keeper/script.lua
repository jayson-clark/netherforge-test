local this = this --[[@as Centity]]

-- The shrine keeper. Nothing spawns one by hand: structures/ruined_shrine.nbt holds a marker
-- tagged nf.centity.shrine_keeper, and each shrine the world generates turns its marker into
-- a keeper, once. The lantern on its staff sways (`idle`, autoplay); it bows to whoever
-- talks to it.
local keeper = require("keeper")
local shrines = require("shrines")

-- Once, when it first appears in its shrine: the chest beside it is the shrine's.
this:on("spawn", function()
  shrines.claim_chest(this)
end)

this:on("click", function(event)
  if event.click == "right" then
    this:emit("keeper:greet", { player = event.player })
  end
end)

this:on("keeper:greet", function(event)
  local position = event.player:position()
  if position then
    this:look_at(position)
  end
  this:play_animation("bow")
  keeper.greet(event.player)
end)
