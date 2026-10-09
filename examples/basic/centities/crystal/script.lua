local this = this --[[@as Centity]]

-- One display entity draws the whole crystal, and set_model swaps the look it
-- shows: a flipbook of two frames, every ten ticks, while `spin` turns it.
local gem = assert(this:node("gem"))
local frames = { "ui/crystal", "ui/crystal_lit" }
local frame = 1

nf.every(10, function()
  frame = frame % #frames + 1
  gem:set_model(frames[frame])
end)

gem:on("click", function(event)
  event.player:send_message(
    "<red>The crystal hums. It's showing " .. (gem:model() or "nothing") .. "."
  )
end)
