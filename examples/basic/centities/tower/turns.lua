local this = this --[[@as Centity]]

-- Required by script.lua. It runs once for each tower, in that tower's
-- script: `this` is the tower, and `count` is its own.
local turns = {}

local count = 0

-- Spins the top with a sparkle around it, and says how many turns this tower has made.
function turns.spin()
  this:play_animation("spin")
  nf.particles.play("sparkle", this:location():offset(vec3(0, 2, 0)))
  count = count + 1
  return count
end

return turns
