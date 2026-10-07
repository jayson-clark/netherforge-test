-- Tests for the shrine keeper and their quest. The quest itself is kept by the vale_lore
-- library this project depends on (its quests module); the keeper module defines it.
local quests = require("vale_lore:quests")
local keeper = require("keeper")

local function give(player, id, count)
  player:give_item(nf.items.create(id, { count = count }))
end

local function count(player, id)
  return player:inventory():count_item({ item = id })
end

nf.test.case("greeting the keeper starts the quest, with a journal to follow it in", function()
  local alex = nf.test.player("Alex")
  assert(quests.status(alex, keeper.QUEST) == "none")
  keeper.greet(alex)
  assert(quests.status(alex, keeper.QUEST) == "active")
  assert(
    count(alex, "vale_lore:journal") == 1,
    "the library hands over a journal with a first quest"
  )
  assert(alex:has_advancement("vale_lore:chronicle"))
  assert(keeper.speech(alex):find("<white>8</white> more shards", 1, true), keeper.speech(alex))
end)

nf.test.case("offered shards count, and the keeper takes no more than the quest asks", function()
  local alex = nf.test.player("Alex")
  keeper.greet(alex)
  give(alex, "lumen_shard", 5)
  keeper.offer(alex)
  assert(quests.count(alex, keeper.QUEST, "shards") == 5)
  assert(count(alex, "lumen_shard") == 0)

  give(alex, "lumen_shard", 10)
  keeper.offer(alex)
  assert(quests.count(alex, keeper.QUEST, "shards") == 8, "the setting's default is 8")
  assert(count(alex, "lumen_shard") == 7, "the rest stay with Alex")
  assert(keeper.offer(alex):find("all the lumen", 1, true))
end)

nf.test.case(
  "shards and three calmed wisps complete it: a lantern, and the Keeper's Friend",
  function()
    local alex = nf.test.player("Alex")
    keeper.greet(alex)
    give(alex, "lumen_shard", 8)
    keeper.offer(alex)
    assert(quests.status(alex, keeper.QUEST) == "active", "the wisps are still to do")

    local here = assert(alex:location())
    for i = 1, 3 do
      local wisp = assert(nf.centities.spawn("wisp", here:offset(vec3(i * 2, 0, 0))))
      wisp:emit("wisp:calm", { player = alex })
      assert(not wisp:exists(), "a calmed wisp is gone")
    end
    assert(quests.status(alex, keeper.QUEST) == "done")
    assert(count(alex, "lumen_lantern") == 1, "the reward is a lantern")
    assert(count(alex, "wisp_essence") >= 3, "each wisp leaves its essence")
    assert(alex:has_advancement("keepers_friend"))
    assert(keeper.speech(alex):find("Kindle the lantern", 1, true))
  end
)

nf.test.case("the keeper answers every topic of the dialog", function()
  for _, topic in ipairs({ "vale", "wisps", "altar", "sky" }) do
    assert(#keeper.answer(topic) > 20, topic)
  end
  assert(keeper.answer("nonsense") == keeper.answer("vale"))
end)

nf.test.case("the keeper's centity bows to whoever greets it", function()
  local alex = nf.test.player("Alex")
  local shrine_keeper =
    assert(nf.centities.spawn("shrine_keeper", assert(alex:location()):offset(vec3(3, 0, 0))))
  assert(shrine_keeper:is_animation_playing("idle"), "the lantern sways by itself")
  shrine_keeper:emit("keeper:greet", { player = alex })
  assert(shrine_keeper:is_animation_playing("bow"))
  assert(quests.status(alex, keeper.QUEST) == "active")
end)
