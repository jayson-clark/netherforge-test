local this = this --[[@as Centity]]

-- A wisp: a scrap of the vale's old light, drifting through the crystal grove at night
-- (centity.json's `spawning`, and the spirits module's say in when). Its `float` animation
-- bobs and turns it; this script lights it, leaves a trail behind it, drifts it about, and
-- lets a player calm it.
local spirits = require("spirits")

local bob = assert(this:node("bob"))
local core = assert(this:node("core"))
local shell = assert(this:node("shell"))

-- The core shows the essence a calmed wisp leaves behind; the item display draws the project
-- item's own look (resource_packs/lumen). Both glow, at full light whatever the time of night.
core:set_display_item(nf.items.create("wisp_essence"))
for _, node in ipairs({ core, shell }) do
  node:set_brightness({ block_light = 15, sky_light = 15 })
end
shell:set_glowing(true)
shell:set_glow_color("#c58cff")

-- The trail follows the light itself (the bobbing node), for as long as this script runs.
local here = this:location()
if here then
  nf.particles.play("wisp_trail", here, { follow = bob, offset = vec3(0, -0.1, 0) })
end

-- Every few seconds it drifts somewhere new, flying round whatever's in the way, and now
-- and then it chimes.
this:on("tick", function()
  if not this:has_path() then
    local target = spirits.next_drift(this)
    if target then
      this:move_to(target, { fly = true, speed = 1.2 })
    end
  end
  if math.random() < 0.15 then
    local position = this:position()
    local world = this:world()
    if position and world then
      world:play_sound("lumen/wisp_chime", position, { volume = 0.6, category = "neutral" })
    end
  end
end, { every = 60 })

-- A right click holds out a hand: the wisp settles (a custom event of its own, so tests and
-- other scripts can calm one without clicking). A left click startles it away.
this:on("click", function(event)
  if event.click == "right" then
    this:emit("wisp:calm", { player = event.player })
  else
    local position = this:position()
    if position then
      this:stop_pathing()
      this:teleport(position + vec3(math.random(-4, 4), 0, math.random(-4, 4)))
    end
  end
end)

this:on("wisp:calm", function(event)
  spirits.calm(event.player, this)
end)
