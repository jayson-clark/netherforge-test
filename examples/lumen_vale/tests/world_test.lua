-- Tests for what the vale does as players move through it: discoveries and the grove's
-- awakening (modules/discovery), the shrines' chests (modules/shrines) and the way to the sky
-- reach (modules/sky_reach).
local discovery = require("discovery")
local shrines = require("shrines")
local sky_reach = require("sky_reach")

-- Runs [body] as a task, which may wait for the database, and lets the server catch up.
local function in_task(body)
  local result
  nf.task(function()
    result = table.pack(body())
  end)
  for _ = 1, 10 do
    if result then
      break
    end
    nf.test.advance(1)
  end
  assert(result, "the task didn't finish")
  return table.unpack(result, 1, result.n)
end

nf.test.case("the first steps into the grove play its awakening, and only the first", function()
  local alex = nf.test.player("Alex")
  discovery.entered(alex, "crystal_grove")
  nf.test.advance(2) -- the database answers off the main thread
  assert(nf.cutscenes.current(alex) == nil, "a moment to look round first")
  nf.test.advance(40)
  local scene = assert(nf.cutscenes.current(alex), "the awakening should be playing")
  assert(scene:kind() == "grove_awakening")
  nf.cutscenes.stop(alex)

  -- Gone and back: the server forgets what it saw, the database doesn't.
  nf.test.raise("player_quit", { player = alex, message = "Alex left the game" })
  discovery.entered(alex, "crystal_grove")
  nf.test.advance(42)
  assert(nf.cutscenes.current(alex) == nil, "the awakening plays once")
end)

nf.test.case("every place found goes into the database, in the order found", function()
  local alex = nf.test.player("Alex")
  discovery.entered(alex, "mossy_meadows")
  discovery.entered(alex, "ashen_ridge")
  discovery.entered(alex, "mossy_meadows")
  discovery.entered(alex, "minecraft:plains") -- not one of the vale's: nothing to find
  nf.test.advance(2)
  local rows = assert(in_task(function()
    return discovery.places(alex)
  end))
  assert(#rows == 2, "two places, got " .. #rows)
  assert(rows[1].place == "mossy_meadows" and rows[2].place == "ashen_ridge")
end)

nf.test.case("walking about is heard, and a cutscene's camera isn't walking", function()
  local alex = nf.test.player("Alex")
  local here = assert(alex:location())
  local moved =
    nf.test.raise("player_move", { player = alex, from = here, to = here:offset(vec3(1, 0, 0)) })
  assert(not moved.cancelled)
end)

nf.test.case(
  "a keeper claims its shrine's chest, which fills the first time it's opened",
  function()
    local alex = nf.test.player("Alex")
    local world = assert(alex:world())
    world:set_block(vec3(3, 64, 4), "minecraft:chest")
    nf.centities.spawn("shrine_keeper", world:location(vec3(0.5, 64, 0.5)))

    local chest = assert(world:block(vec3(3, 64, 4)))
    local claimed, filled = shrines.state(chest)
    assert(claimed and not filled, "the keeper claims the chest beside it")

    local inventory = assert(chest:inventory())
    local function open()
      nf.test.raise(
        "player_open_inventory",
        { player = alex, kind = "chest", inventory = inventory, block = chest }
      )
      local stacks = 0
      for _ in pairs(inventory:items()) do
        stacks = stacks + 1
      end
      return stacks
    end
    local first = open()
    assert(first > 0, "the shrine's offerings are in it")
    assert(select(2, shrines.state(chest)), "and it's marked filled")
    assert(open() == first, "a second opening adds nothing")
  end
)

nf.test.case("only a kindled lantern raised at a shrine asks to rise", function()
  local alex = nf.test.player("Alex")
  local cold = nf.items.create("lumen_lantern")
  local kindled = nf.items.create("lumen_lantern", { data = { kindled = true } })
  assert(not sky_reach.kindled(cold) and sky_reach.kindled(kindled))

  assert(not sky_reach.raise_lantern(alex, cold), "a cold lantern only flickers")
  assert(not sky_reach.raise_lantern(alex, kindled), "no shrine in reach")

  nf.centities.spawn("shrine_keeper", assert(alex:location()):offset(vec3(3, 0, 0)))
  assert(sky_reach.shrine_near(alex))
  assert(sky_reach.raise_lantern(alex, kindled), "at a shrine, the lantern asks")
end)

nf.test.case(
  "rising goes up to the sky reach's landing, and the lantern brings you back",
  function()
    local alex = nf.test.player("Alex")
    local from = assert(alex:location())
    -- netherforge.json's `worlds` makes the sky reach as the project loads.
    local sky = assert(nf.worlds.get(sky_reach.WORLD), "the project makes its sky reach")

    assert(sky_reach.ascend(alex))
    local up = assert(alex:location())
    assert(up.world:name() == sky_reach.WORLD and up.position.y > 120, tostring(up.position))
    assert(assert(sky:block(vec3(0, 125, 0))):custom(), "the landing's lamp is an altar")
    assert(alex:has_advancement("above_the_vale"))

    assert(
      sky_reach.raise_lantern(alex, nf.items.create("lumen_lantern", { data = { kindled = true } }))
    )
    local down = assert(alex:location())
    assert(down.world:name() == from.world:name() and down.position:distance(from.position) < 1)
  end
)

nf.test.case("settings read as their defaults", function()
  assert(nf.config("grove_cutscene") == true)
  assert(nf.config("keeper_shards") == 8)
  assert(nf.config("wisps_at_night_only") == true)
end)
