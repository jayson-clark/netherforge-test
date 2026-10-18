-- Booth: mobs and their AI. The game's own mobs, spawned and steered by scripts: goals written in
-- Lua run in the mob's own AI beside the game's, with priorities and the controls they claim.
local expo = require("expo")

-- The nearest player within `range` blocks of a mob who holds `kind` in their hand.
local function holder(mob, kind, range)
  local here = mob:position()
  local world = mob:world()
  if not here or not world then
    return nil
  end
  for _, player in ipairs(world:players()) do
    local held = player:held_item()
    local at = player:position()
    if
      held
      and at
      and at:distance(here) < range
      and (nf.items.id(held) == kind or held.kind == kind)
    then
      return player
    end
  end
  return nil
end

expo.booth({
  id = "mobs",
  title = "Mobs & AI goals",
  summary = "The game's mobs, with goals written in Lua",
  icon = "minecraft:sheep_spawn_egg",
  lines = {
    "<gold>Mobs</gold>: world:spawn_entity, then mob:add_goal.",
    "",
    "<gray>A goal says when it should start and continue, what it does each tick,",
    "<gray>its priority (smaller goes first) and which controls it claims (move,",
    "<gray>look, jump, target), so the game's own goals make way for it.",
    "",
    "<white>The sheep</white> <gray>has a goal: it follows anyone holding a ruby.",
    "<white>The pig</white> <gray>has had every goal cleared, and one added: it walks",
    "<gray>back and forth between the two posts, and nothing else.",
  },
  build = function(booth)
    expo.world:fill_blocks(booth:cell(3, 1, 1), booth:cell(9, 1, 1), "minecraft:oak_fence")
    expo.world:fill_blocks(booth:cell(3, 1, 11), booth:cell(9, 1, 11), "minecraft:oak_fence")
    expo.world:fill_blocks(booth:cell(3, 1, 1), booth:cell(3, 1, 11), "minecraft:oak_fence")
    expo.world:set_block(booth:cell(3, 1, 6), "minecraft:air")

    local sheep =
      booth:spawn_entity("minecraft:sheep", 6, 1, 3, { custom_name = "<white>Follows rubies" }) --[[@as Mob?]]
    if sheep then
      sheep:add_goal("follow_ruby", {
        priority = 2,
        controls = { "move", "look" },
        should_start = function(mob)
          return holder(mob, "ruby", 10) ~= nil
        end,
        tick = function(mob)
          local player = holder(mob, "ruby", 10)
          local at = player and player:position()
          if at then
            mob:move_to(at, { speed = 1.2 })
          end
        end,
        stop = function(mob)
          mob:stop_pathing()
        end,
      })
    end
    booth:caption(
      4,
      3,
      "<white>Sheep\n<gray>A Lua goal: hold a ruby (from the items booth) near it.",
      3
    )

    local posts = { booth:pos(5, 1, 8), booth:pos(8, 1, 8) }
    for _, post in ipairs(posts) do
      expo.world:set_block(post - vec3(0, 0, 0), "minecraft:oak_log")
    end
    local pig =
      booth:spawn_entity("minecraft:pig", 6.5, 1, 9, { custom_name = "<light_purple>Patrols" }) --[[@as Mob?]]
    if pig then
      pig:clear_goals()
      local going = 1
      pig:add_goal("patrol", {
        priority = 1,
        controls = { "move" },
        tick = function(mob)
          if not mob:has_path() then
            going = going % 2 + 1
            mob:move_to(posts[going] + vec3(0, 0, 1), { speed = 1 })
          end
        end,
      })
    end
    booth:caption(4, 9, "<white>Pig\n<gray>clear_goals, then one goal: patrol between the logs.", 3)
  end,
})
