local this = this --[[@as ProjectItem]]

-- Raising a lantern. A cold one only flickers; one kindled at an altar (its stack's data says
-- `kindled`) opens the way to the sky reach at a shrine, and back down again from up there.
-- What that means lives in modules/sky_reach, so tests and commands can do the same.
local sky_reach = require("sky_reach")

this:on("use", function(event)
  event:cancel()
  sky_reach.raise_lantern(event.player, event.item)
end)
