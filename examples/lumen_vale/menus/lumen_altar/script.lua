local this = this --[[@as Menu]]

-- The lumen altar. Every player who opens one gets a window of their own, with this script.
-- The skin (resource_packs/lumen's `altar`) draws a ring round the middle slot: what's set
-- there is what gets infused, and the shard below it is the button. The rules are the altar
-- module's.
local altar = require("altar")

local RING, BUTTON = 13, 22

-- What opened it: a lamp's click hands over where the lamp is (blocks/lumen_lamp).
local context = this:context() or {}

-- The button's lore says what pressing it would do with what's in the ring.
local function refresh()
  this:set_item(
    BUTTON,
    nf.items.create(
      "lumen_shard",
      { name = "<light_purple>Infuse", lore = altar.describe(this:item(RING)) }
    )
  )
end
refresh()

this:on("open", function(event)
  event.player:play_sound("lumen/altar_hum", { category = "block" })
end)

-- The window is locked, so clicks move nothing, except in the ring: putting an item in and
-- taking it out go through. The click lands after the handlers, so the button catches up a
-- tick later.
this:slot(RING):on("click", function(event)
  if event.click == "left" or event.click == "right" then
    event:uncancel()
    nf.after(1, refresh)
  end
end)

this:slot(BUTTON):on("click", function(event)
  local player = event.player
  local item = this:item(RING)
  if not item then
    player:send_actionbar("<gray>Set something in the ring first")
    return
  end
  local result, rest = altar.infuse(player, item)
  if not result then
    player:send_actionbar("<red>" .. tostring(rest))
    player:play_sound("minecraft:block.amethyst_block.hit")
    return
  end
  this:set_item(RING, result)
  if type(rest) == "table" then
    player:give_item(rest)
  end
  local at = context.lamp and context.lamp:offset(vec3(0.5, 1, 0.5)) or player:location()
  if at then
    nf.particles.play("infusion_burst", at)
    at.world:play_sound("lumen/infuse", at.position, { category = "block" })
  end
  refresh()
end)

-- Whatever is left in the ring goes back to whoever closes the window, so nothing is lost.
this:on("close", function(event)
  local item = this:item(RING)
  if item then
    this:set_item(RING, nil)
    event.player:give_item(item)
  end
end)
