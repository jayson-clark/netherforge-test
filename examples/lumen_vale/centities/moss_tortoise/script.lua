local this = this --[[@as Centity]]

-- A moss tortoise: it ambles about the meadows on its own (centity.json's `spawning` brings
-- it), and a player can feed it. What feeding does is the meadows module's.
local meadows = require("meadows")

local data = assert(this:data())
local label = assert(this:node("label"))

-- A tortoise someone has fed is theirs, and says so.
local function show_friend()
  label:set_display_text(
    data.friend and ("<green>%s's tortoise"):format(data.friend) or "<green>Moss Tortoise"
  )
end
show_friend()

-- Every five seconds or so, perhaps a short walk: a few blocks off, slowly, legs going.
this:on("tick", function()
  if this:has_path() or math.random() > 0.35 then
    return
  end
  local here = this:position()
  if
    here and this:move_to(here + vec3(math.random(-6, 6), 0, math.random(-6, 6)), { speed = 0.8 })
  then
    this:play_animation("walk")
  end
end, { every = 100 })

this:on("path_end", function()
  this:stop_animation("walk")
end)

-- A right click holds out what's in your hand (a custom event of its own, so tests can feed
-- one without clicking).
this:on("click", function(event)
  if event.click == "right" then
    this:emit("tortoise:feed", { player = event.player })
  end
end)

this:on("tortoise:feed", function(event)
  if meadows.feed(event.player, this) then
    show_friend()
  end
end)
