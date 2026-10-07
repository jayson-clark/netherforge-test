local this = this --[[@as Centity]]

-- Turning lives in turns.lua, beside this script. A file beside a centity's
-- script runs as part of it, so every tower has its own copy: each counts
-- its own turns.
local turns = require("turns")

-- Clicking any part of the tower spins its top, with a sparkle around it.
this:on("click", function(event)
  local count = turns.spin()
  event.player:send_message("<gold>The tower turns.")
  event.player:send_actionbar("<gray>Turns of this tower: " .. count)
end)
