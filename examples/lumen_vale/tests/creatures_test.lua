-- Tests for the vale's creatures: the wisps (modules/spirits, centities/wisp) and the moss
-- tortoises (modules/meadows, centities/moss_tortoise).
local spirits = require("spirits")

local function count(player, id)
  return player:inventory():count_item({ item = id })
end

nf.test.case("wisps are refused by day and come out at night", function()
  local alex = nf.test.player("Alex")
  local here = assert(alex:location())
  local world = here.world
  local function spawn(kind)
    return nf.test.raise(
      "centity_natural_spawn",
      { centity = kind, location = here, player = alex }
    )
  end

  world:set_time_of_day(6000)
  assert(not spirits.awake(world))
  assert(spawn("wisp").cancelled, "no wisps at noon")
  assert(not spawn("moss_tortoise").cancelled, "tortoises don't mind")

  world:set_time_of_day(18000)
  assert(spirits.is_night(world) and spirits.awake(world))
  assert(not spawn("wisp").cancelled)
end)

nf.test.case("a wisp drifts towards someone wearing a lumen circlet", function()
  local alex = nf.test.player("Alex")
  local here = assert(alex:location())
  alex:set_equipment("head", nf.items.create("lumen_circlet"))
  local wisp = assert(nf.centities.spawn("wisp", here:offset(vec3(6, 0, 0))))
  local target = assert(spirits.next_drift(wisp))
  assert(target:distance(here.position) < 3, "drifting to " .. tostring(target))
end)

nf.test.case("a calmed wisp leaves its essence and is gone", function()
  local alex = nf.test.player("Alex")
  local wisp = assert(nf.centities.spawn("wisp", assert(alex:location()):offset(vec3(2, 0, 0))))
  assert(wisp:is_animation_playing("float"))
  wisp:emit("wisp:calm", { player = alex })
  assert(not wisp:exists())
  assert(count(alex, "wisp_essence") == 1)
end)

nf.test.case(
  "a tortoise eats sweet berries, keeps the feeder as a friend, and chews a while",
  function()
    local alex = nf.test.player("Alex")
    local tortoise =
      assert(nf.centities.spawn("moss_tortoise", assert(alex:location()):offset(vec3(2, 0, 0))))

    tortoise:emit("tortoise:feed", { player = alex })
    assert(tortoise:data().friend == nil, "an empty hand feeds nobody")

    alex:set_held_item({ kind = "minecraft:sweet_berries", count = 2 })
    tortoise:emit("tortoise:feed", { player = alex })
    assert(tortoise:data().friend == "Alex")
    assert(assert(alex:held_item()).count == 1, "one berry eaten")
    assert(tortoise:is_animation_playing("munch"))
    assert(assert(tortoise:node("label")):display_text() == "<green>Alex's tortoise")

    tortoise:emit("tortoise:feed", { player = alex })
    assert(assert(alex:held_item()).count == 1, "still chewing: the second berry stays")
  end
)
