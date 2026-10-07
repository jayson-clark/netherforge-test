-- Probes the lumen vale scenario runs from the console, each logging what it found.
local quests = require("vale_lore:quests")
local keeper = require("keeper")
local shrines = require("shrines")
local sky_reach = require("sky_reach")
local discovery = require("discovery")

local steps = {}

local function world(name)
  return assert(nf.worlds.get(name ~= "" and name or "world"), "no world " .. tostring(name))
end

local function player(name)
  return assert(nf.players.get(name), "no player " .. name)
end

local function floored(v)
  return ("%d %d %d"):format(math.floor(v.x), math.floor(v.y), math.floor(v.z))
end

-- world <name>: its heights, its spawn and the biome there.
function steps.world(name)
  local w = world(name)
  local spawn = w:spawn_location().position
  w:load_chunk(spawn)
  local block = w:block(spawn)
  log(
    "world",
    w:name(),
    w:min_height(),
    w:max_height(),
    floored(spawn),
    block and block:biome() or "?"
  )
end

-- locate <world> <biome>: the nearest place the world's generator puts a biome.
function steps.locate(name, biome)
  local w = world(name)
  nf.task(function()
    local at, err = w:locate_biome(biome, { radius = 4000 })
    log("locate", biome, at and floored(at) or ("none " .. tostring(err)))
  end)
end

-- column <world> <x> <z>: the top block of a column, what it is and the biome there.
function steps.column(name, x, z)
  local w = world(name)
  local at = vec3(tonumber(x), 0, tonumber(z))
  w:load_chunk(at)
  local top = assert(w:highest_block(at))
  log("column", floored(top:position()), top:kind(), top:biome() or "?")
end

-- centities <kind>: how many there are, and the first's id, place and whether it's natural.
function steps.centities(kind)
  local all = nf.centities.all({ kind = kind })
  local first = all[1]
  log(
    "centities",
    kind,
    #all,
    first and first:id() or "-",
    first and floored(first:position()) or "-",
    first and tostring(first:is_natural()) or "-"
  )
end

-- shrine: each keeper's chest, whether the keeper claimed it and whether it's been filled.
function steps.shrine()
  for _, k in ipairs(nf.centities.all({ kind = "shrine_keeper" })) do
    local location = assert(k:location())
    location.world:load_chunk(location.position)
    local found = "none"
    for dx = -5, 5 do
      for dy = -2, 2 do
        for dz = -5, 5 do
          local block = location.world:block(location.position:floor() + vec3(dx, dy, dz))
          if block and block:kind() == "minecraft:chest" then
            local claimed, filled = shrines.state(block)
            found = ("%s %s %s"):format(
              floored(block:position()),
              tostring(claimed),
              tostring(filled)
            )
          end
        end
      end
    end
    log("shrine", k:id(), floored(location.position), found)
  end
  log("shrine", "done")
end

-- give <player> <item> <count>: a stack of a project item.
function steps.give(name, item, count)
  player(name):give_item(nf.items.create(item, { count = tonumber(count) or 1 }))
  log("gave", name, item)
end

-- lamp <player>: a lumen lamp (an altar) two blocks east of them, on the ground.
function steps.lamp(name)
  local location = assert(player(name):location())
  local at = location.position:floor() + vec3(2, 0, 0)
  local placed = assert(nf.blocks.get("lumen_lamp")):place(location.world:location(at))
  log("lamp", placed and floored(placed:position()) or "none")
end

-- quest <player>: where they are with the keeper's quest.
function steps.quest(name)
  local p = player(name)
  log(
    "quest",
    quests.status(p, keeper.QUEST),
    quests.count(p, keeper.QUEST, "shards"),
    quests.count(p, keeper.QUEST, "wisps")
  )
end

-- time <world> <ticks>
function steps.time(name, ticks)
  world(name):set_time_of_day(tonumber(ticks))
  log("time", name, ticks)
end

-- block <world> <x> <y> <z>: what is there, and which of the project's blocks it is.
function steps.block(name, x, y, z)
  local w = world(name)
  local at = vec3(tonumber(x), tonumber(y), tonumber(z))
  w:load_chunk(at)
  local block = assert(w:block(at))
  local custom = block:custom()
  log("block", block:kind(), custom and custom:id() or "none")
end

-- landing: the sky reach's landing's lamp.
function steps.landing()
  local sky = world(sky_reach.WORLD)
  sky:load_chunk(vec3(0, 125, 0))
  local block = assert(sky:block(vec3(0, 125, 0)))
  local custom = block:custom()
  log("landing", block:kind(), custom and custom:id() or "none")
end

-- places <player>: what the discovery module's database says they've found.
function steps.places(name)
  local p = player(name)
  nf.task(function()
    local rows = discovery.places(p) or {}
    local names = {}
    for _, row in ipairs(rows) do
      names[#names + 1] = row.place
    end
    log("places", table.concat(names, ","))
  end)
end

-- advancements <player>: which of the vale's they have.
function steps.advancements(name)
  local p = player(name)
  local done = {}
  for _, id in ipairs({
    "vale_lore:chronicle",
    "first_light",
    "altar_tender",
    "keepers_friend",
    "above_the_vale",
  }) do
    if p:has_advancement(id) then
      done[#done + 1] = id
    end
  end
  log("advancements", table.concat(done, ","))
end

-- awaken <player>: plays the grove's awakening for them, as their first steps there do.
function steps.awaken(name)
  local p = player(name)
  local ok, scene = pcall(discovery.awaken, p)
  local current = nf.cutscenes.current(p)
  log("awaken", tostring(ok), tostring(scene), current and current:kind() or "none", p:game_mode())
end

-- What the project's own events say as they happen.
for _, event in ipairs({
  "lumen_vale:discovered",
  "lumen_vale:infused",
  "lumen_vale:ascended",
  "lumen_vale:wisp_calmed",
}) do
  nf.on(event, function(raised)
    log("event", event, tostring(raised.place or raised.rule or ""))
  end)
end

nf.commands.register("it-vale", {
  arguments = {
    { name = "step", type = "word" },
    { name = "a", type = "word", default = "" },
    { name = "b", type = "word", default = "" },
    { name = "c", type = "word", default = "" },
    { name = "d", type = "word", default = "" },
  },
}, function(event)
  local args = event.arguments
  steps[args.step](args.a, args.b, args.c, args.d)
end)
