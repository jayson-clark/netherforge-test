-- Tests for the quests module, run by `netherforge test` on a fake server with the library
-- loaded as a project of its own. Every test starts a fresh server, so every player starts
-- with no quests.
local quests = require("quests")

-- A quest of the tests' own, defined the way a project defines its quests.
quests.define("vale_lore:trial", {
  title = "<gold>The Trial",
  summary = "Gather stones, then say the words.",
  objectives = {
    stones = { label = "Stones gathered", goal = 3 },
    words = { label = "Words spoken", goal = 1 },
  },
  order = { "stones", "words" },
  rewards = { items = { nf.items.create("old_coin", { count = 2 }) }, experience = 5 },
})

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

nf.test.case("starting a quest hands over a journal once, and starts the chronicle", function()
  local alex = nf.test.player("Alex")
  assert(quests.status(alex, "vale_lore:trial") == "none")
  assert(quests.start(alex, "vale_lore:trial"))
  assert(quests.status(alex, "vale_lore:trial") == "active")
  assert(
    alex:inventory():count_item({ item = "journal" }) == 1,
    "the first quest comes with a journal"
  )
  assert(alex:has_advancement("chronicle"))
  assert(not quests.start(alex, "vale_lore:trial"), "a quest starts once")
  assert(alex:inventory():count_item({ item = "journal" }) == 1)
end)

nf.test.case(
  "objectives count up to their goal, and meeting them all completes the quest",
  function()
    local alex = nf.test.player("Alex")
    local completed
    nf.on("vale_lore:quest_completed", function(event)
      completed = event
    end)
    quests.start(alex, "vale_lore:trial")

    assert(quests.advance(alex, "vale_lore:trial", "stones", 5))
    assert(quests.count(alex, "vale_lore:trial", "stones") == 3, "progress stops at the goal")
    assert(not quests.advance(alex, "vale_lore:trial", "stones"), "a met objective doesn't move")
    assert(quests.status(alex, "vale_lore:trial") == "active")

    assert(quests.advance(alex, "vale_lore:trial", "words"))
    assert(quests.status(alex, "vale_lore:trial") == "done")
    assert(completed and completed.quest == "vale_lore:trial" and completed.player == alex)
    assert(alex:inventory():count_item({ item = "old_coin" }) == 2, "the reward is handed over")
    assert(quests.remaining(alex, "vale_lore:trial", "stones") == 0)
  end
)

nf.test.case("a quest nobody started can't be advanced, and a wrong name is an error", function()
  local alex = nf.test.player("Alex")
  assert(not quests.advance(alex, "vale_lore:trial", "stones"))
  assert(not pcall(quests.advance, alex, "vale_lore:trial", "pebbles"))
  assert(not pcall(quests.status, alex, "vale_lore:nothing"))
end)

nf.test.case("the journal lists active quests with their objectives, and finished ones", function()
  local alex = nf.test.player("Alex")
  assert(quests.journal_text(alex):find("No quests yet", 1, true))
  quests.start(alex, "vale_lore:trial")
  quests.advance(alex, "vale_lore:trial", "stones")
  local text = quests.journal_text(alex)
  assert(text:find("The Trial", 1, true), text)
  assert(text:find("Stones gathered <gray>1/3", 1, true), text)
  quests.complete(alex, "vale_lore:trial")
  assert(quests.journal_text(alex):find("<green>✔ <gray><gold>The Trial", 1, true))
end)

nf.test.case("the ledger in the library's database records the start and the finish", function()
  local alex = nf.test.player("Alex")
  quests.start(alex, "vale_lore:trial")
  quests.complete(alex, "vale_lore:trial")
  nf.test.advance(2) -- the database writes off the main thread
  local rows = assert(in_task(function()
    return quests.history(alex)
  end))
  assert(#rows == 2, "two lines, got " .. #rows)
  assert(rows[1].event == "started" and rows[2].event == "completed")
  assert(rows[2].title == "<gold>The Trial")
end)

nf.test.case("trinkets are old coins and moonstones, and sometimes nothing", function()
  local coins, moonstones = 0, 0
  for seed = 1, 80 do
    for _, item in ipairs(nf.loot.roll("trinkets", { seed = seed })) do
      local id = nf.items.id(item)
      if id == "old_coin" then
        coins = coins + item.count
      elseif id == "moonstone" then
        moonstones = moonstones + 1
      else
        error("trinkets gave " .. tostring(id or item.kind))
      end
    end
  end
  assert(coins > 0 and moonstones > 0, ("%d coins, %d moonstones"):format(coins, moonstones))
end)

nf.test.case("settings read as their defaults", function()
  assert(nf.config("announce_completions") == true)
  assert(nf.config("journal_on_first_quest") == true)
end)
