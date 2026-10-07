local this = this --[[@as Centity]]

-- A crate that drops from where it's spawned, bounces a little and settles.
-- Click it to shove it where you clicked: it topples rather than slides.
local box = assert(this:node("box"))

box:on("sleep", function()
  log(("crate settled at %.3f"):format(box:translation().y))
end)

box:on("click", function(event)
  -- Where your line of sight met it, or the middle of its top face, wherever it has rolled to.
  local at = event.hit_position or box:to_world(vec3(0.5, 1, 0.5))
  if at == nil then
    return
  end
  box:apply_impulse_at(vec3(0, 4, 3), at)
end)
