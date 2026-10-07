local this = this --[[@as Dialog]]

-- Asked when a kindled lantern is raised at a shrine (modules/sky_reach): a confirmation's two
-- buttons, yes and no.
local sky_reach = require("sky_reach")

this:button("rise"):on("press", function(event)
  sky_reach.ascend(event.player)
end)

this:button("stay"):on("press", function(event)
  event.player:send_actionbar("<gray>The lantern settles. It will wait.")
end)
