local this = this --[[@as Centity]]

-- A door that swings open when clicked, waits, and swings shut: a little
-- cutscene, written top to bottom as a task. A handler can't wait, so the
-- click starts a task that does. A shockwave plays first, and the door opens
-- once it's over.
local cutscene = nil

this:on("click", function(event)
  if cutscene ~= nil and cutscene:is_active() then
    return
  end
  local player = event.player
  cutscene = nf.task(function()
    local here = this:location()
    local shockwave = here and nf.particles.play("shockwave", here)
    if shockwave ~= nil then
      nf.wait_for(shockwave, "end")
    end
    this:play_animation("open")
    nf.wait_for(this, "animation_end")
    player:send_message("<gray>The door creaks open.")
    nf.wait(40)
    this:play_animation("close")
    nf.wait_for(this, "animation_end")
  end)
end)
