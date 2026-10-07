local this = this --[[@as ProjectItem]]

-- Wisp essence is edible (item.json's `food`): drinking it lets you see in the dark for a
-- while, and the wisps' chime goes with it.
this:on("consume", function(event)
  event.player:add_effect("minecraft:night_vision", 60 * 20, { ambient = true })
  event.player:play_sound("lumen/wisp_chime")
end)
