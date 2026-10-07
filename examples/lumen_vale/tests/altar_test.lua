-- Tests for the lumen altar: its rules (modules/altar) and its window (menus/lumen_altar), on a
-- fake server. Every test starts a fresh one, so every player starts with an empty inventory.
local altar = require("altar")

local function give(player, id, count)
  player:give_item(nf.items.create(id, { count = count }))
end

local function count(player, id)
  return player:inventory():count_item({ item = id })
end

nf.test.case("a cold lantern is kindled for three shards, and only once", function()
  local alex = nf.test.player("Alex")
  give(alex, "lumen_shard", 5)
  local lantern = nf.items.create("lumen_lantern")
  assert(assert(altar.rule_for(lantern)).name == "kindle")

  local kindled, rest = altar.infuse(alex, lantern)
  assert(kindled and kindled.data and kindled.data.kindled == true, "the lantern should be kindled")
  assert(kindled.glint and nf.items.id(kindled) == "lumen_lantern")
  assert(rest == nil, "one lantern, nothing left over")
  assert(count(alex, "lumen_shard") == 2, "three of five shards are taken")
  assert(altar.rule_for(kindled) == nil, "a kindled lantern has nothing more to take")
end)

nf.test.case(
  "nothing is taken when the altar can't infuse, and the circlet needs essence too",
  function()
    local alex = nf.test.player("Alex")
    give(alex, "lumen_shard", 4)
    local helmet = { kind = "minecraft:golden_helmet" }

    local result, why = altar.infuse(alex, helmet)
    assert(result == nil and tostring(why):find("wisp essence", 1, true), tostring(why))
    assert(count(alex, "lumen_shard") == 4, "a refused infusion takes nothing")

    give(alex, "wisp_essence", 1)
    local circlet = assert(altar.infuse(alex, helmet))
    assert(nf.items.id(circlet) == "lumen_circlet")
    assert(count(alex, "lumen_shard") == 0 and count(alex, "wisp_essence") == 0)
  end
)

nf.test.case("a stack in the ring is infused one at a time", function()
  local alex = nf.test.player("Alex")
  give(alex, "lumen_shard", 2)
  local essence, rest = altar.infuse(alex, { kind = "minecraft:glass_bottle", count = 3 })
  assert(nf.items.id(assert(essence)) == "wisp_essence")
  assert(type(rest) == "table" and rest.count == 2, "two bottles go back")
end)

nf.test.case("the altar doesn't answer to anything else", function()
  local alex = nf.test.player("Alex")
  give(alex, "lumen_shard", 10)
  assert(altar.rule_for({ kind = "minecraft:diamond" }) == nil)
  local result, why = altar.infuse(alex, { kind = "minecraft:diamond" })
  assert(result == nil and type(why) == "string")
  assert(altar.describe({ kind = "minecraft:diamond" })[1]:find("doesn't answer", 1, true))
end)

nf.test.case("infusing anything earns Altar Tender", function()
  local alex = nf.test.player("Alex")
  give(alex, "lumen_shard", 2)
  assert(not alex:has_advancement("altar_tender"))
  altar.infuse(alex, { kind = "minecraft:glass_bottle" })
  assert(alex:has_advancement("altar_tender"))
end)

nf.test.case(
  "the altar's window shows what the button would do, and gives back the ring on close",
  function()
    local alex = nf.test.player("Alex")
    local menu = assert(alex:open_menu("lumen_altar"))
    local button = assert(menu:item(22))
    assert(nf.items.id(button) == "lumen_shard" and button.name == "<light_purple>Infuse")
    assert(button.lore[1] == "<gray>Set something in the ring:", tostring(button.lore[1]))

    menu:set_item(13, nf.items.create("lumen_lantern"))
    alex:close_menu()
    nf.test.advance(2)
    assert(count(alex, "lumen_lantern") == 1, "what was in the ring comes back")
  end
)
