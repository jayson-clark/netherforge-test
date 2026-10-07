-- Quests: what a player has been asked to do, how far they've got, and what they're given
-- for it. The library keeps the bookkeeping, and the projects that depend on it bring the
-- quests: each declares its own with quests.define and moves them on from its own scripts
-- with quests.advance. netherforge.json exports this module, so a project requires it as
-- require("vale_lore:quests").
--
-- Where things are kept:
-- - what a player is doing now is in their saved table (player:data().vale_lore), so it's
--   there at once, in any script, without waiting for anything;
-- - every quest started and finished goes into the library's own database (nf.db(), its
--   migrations/001_quest_log.sql), the ledger the journal reads back as the player's history.

local quests = {}

local db = nf.db()

-- The library's own settings (its netherforge.json). Listening for setting_changed keeps this
-- module running when the server's owner changes one: a restart would forget every quest the
-- projects defined.
local announce = nf.config("announce_completions")
local hand_journal = nf.config("journal_on_first_quest")
nf.on("setting_changed", function(event)
  if event.setting == "announce_completions" then
    announce = event.value
  elseif event.setting == "journal_on_first_quest" then
    hand_journal = event.value
  end
end)

---@class QuestObjective
---@field label string What the journal calls it, like "Lumen shards offered".
---@field goal integer How many it takes: at least 1.

---@class QuestRewards
---@field items Item[]? Stacks handed over on completing it. Build them in your own script (`nf.items.create("ruby")`), so your item names mean your items.
---@field experience integer? Experience points.

---@class QuestDefinition
---@field title string Its name, MiniMessage: the journal's heading and the announcement's.
---@field summary string? A line on what it's about, MiniMessage.
---@field objectives table<string, QuestObjective> What it takes, by key. Every one met completes it.
---@field order string[]? The order the journal lists the objectives in. Default: by key.
---@field rewards QuestRewards? What completing it gives.
---@field on_complete fun(player: Player)? Called once it's complete, after the rewards.

---@type table<string, QuestDefinition>
local definitions = {}

---@type string[]
local defined_order = {}

local function now()
  return nf.server.unix_time()
end

-- The player's part of their saved table: quests by id, each { status, progress, started, finished }.
local function entries(player)
  local data = player:data()
  data.vale_lore = data.vale_lore or {}
  data.vale_lore.quests = data.vale_lore.quests or {}
  return data.vale_lore.quests
end

local function definition(id)
  return definitions[id] or error(("no quest %q: define it first with quests.define"):format(id), 3)
end

-- Writes a line to the ledger. Nobody waits for it: a handler can't, so the write takes a
-- callback that only reports a failure.
local function record(player, id, event)
  db:execute(
    "INSERT INTO quest_log (player, quest, event, at) VALUES (?, ?, ?, ?)",
    { player:id(), id, event, now() },
    function(_, err)
      if err then
        log(("couldn't log %s's quest %s %s: %s"):format(player:name(), id, event, err))
      end
    end
  )
end

--- Declares a quest. Its id is the server's, shared by every package, so start it with your
--- namespace (`"lumen_vale:rekindle"`). Defining one again replaces it, which is what a
--- module that restarts does.
---@param id string
---@param quest QuestDefinition
function quests.define(id, quest)
  assert(type(id) == "string" and id ~= "", "a quest needs an id")
  assert(type(quest.title) == "string", ("quest %q needs a title"):format(id))
  assert(
    type(quest.objectives) == "table" and next(quest.objectives),
    ("quest %q needs objectives"):format(id)
  )
  local order = quest.order
  if not order then
    order = {}
    for key in pairs(quest.objectives) do
      order[#order + 1] = key
    end
    table.sort(order)
  end
  for _, key in ipairs(order) do
    local objective = quest.objectives[key]
    assert(objective, ("quest %q orders %q, which isn't one of its objectives"):format(id, key))
    assert(
      math.type(objective.goal) == "integer" and objective.goal >= 1,
      ("quest %q's %q needs a goal of at least 1"):format(id, key)
    )
  end
  if not definitions[id] then
    defined_order[#defined_order + 1] = id
  end
  definitions[id] = {
    title = quest.title,
    summary = quest.summary,
    objectives = quest.objectives,
    order = order,
    rewards = quest.rewards or {},
    on_complete = quest.on_complete,
  }
end

--- Whether a quest is defined.
---@param id string
---@return boolean
function quests.exists(id)
  return definitions[id] ~= nil
end

--- How far a player is with a quest: `"none"` (not started), `"active"` or `"done"`.
---@param player Player
---@param id string
---@return "none"|"active"|"done"
function quests.status(player, id)
  definition(id)
  local entry = entries(player)[id]
  return entry and entry.status or "none"
end

--- How many of an objective a player has done.
---@param player Player
---@param id string
---@param key string
---@return integer
function quests.count(player, id, key)
  local quest = definition(id)
  assert(quest.objectives[key], ("quest %q has no objective %q"):format(id, key))
  local entry = entries(player)[id]
  return entry and entry.progress[key] or 0
end

--- How many more of an objective a player needs: 0 once it's met (or the quest is done).
---@param player Player
---@param id string
---@param key string
---@return integer
function quests.remaining(player, id, key)
  local quest = definition(id)
  local entry = entries(player)[id]
  if entry and entry.status == "done" then
    return 0
  end
  return quest.objectives[key].goal - quests.count(player, id, key)
end

--- Starts a quest for a player. `false` when they've started it before (active or done).
--- The first quest anyone starts begins the library's chronicle (its advancement tab), and
--- hands them a journal unless the server's owner turned that off.
---@param player Player
---@param id string
---@return boolean
function quests.start(player, id)
  local quest = definition(id)
  local all = entries(player)
  if all[id] then
    return false
  end
  local first = next(all) == nil
  all[id] = { status = "active", progress = {}, started = now() }
  record(player, id, "started")
  player:send_message("<gold>New quest: </gold>" .. quest.title)
  player:grant_advancement("chronicle", "begun")
  if first and hand_journal and not player:inventory():has_item({ item = "journal" }) then
    player:give_item(nf.items.create("journal"))
    player:send_message("<gray>A journal to keep it in. Right-click it to read.")
  end
  nf.emit("vale_lore:quest_started", { player = player, quest = id })
  return true
end

local function finish(player, id, quest, entry)
  entry.status = "done"
  entry.finished = now()
  record(player, id, "completed")
  for _, item in ipairs(quest.rewards.items or {}) do
    player:give_item(item)
  end
  if quest.rewards.experience then
    player:give_experience(quest.rewards.experience)
  end
  local line = "<gold>" .. nf.text.escape(player:name()) .. " completed </gold>" .. quest.title
  if announce then
    nf.server.broadcast(line)
  else
    player:send_message(line)
  end
  if quest.on_complete then
    quest.on_complete(player)
  end
  nf.emit("vale_lore:quest_completed", { player = player, quest = id })
end

local function all_met(quest, entry)
  for key, objective in pairs(quest.objectives) do
    if (entry.progress[key] or 0) < objective.goal then
      return false
    end
  end
  return true
end

--- Moves an objective of an active quest on by `amount` (default 1), up to its goal, and
--- completes the quest once every objective is met: the rewards, `on_complete`, the
--- announcement and `vale_lore:quest_completed`. `false` when nothing changed: the quest
--- isn't active for them, or the objective was already met.
---@param player Player
---@param id string
---@param key string
---@param amount integer?
---@return boolean
function quests.advance(player, id, key, amount)
  local quest = definition(id)
  local objective = quest.objectives[key]
    or error(("quest %q has no objective %q"):format(id, key), 2)
  local entry = entries(player)[id]
  if not entry or entry.status ~= "active" then
    return false
  end
  local before = entry.progress[key] or 0
  local after = math.min(objective.goal, before + (amount or 1))
  if after == before then
    return false
  end
  entry.progress[key] = after
  player:send_actionbar(("<gold>%s <white>%d/%d"):format(objective.label, after, objective.goal))
  nf.emit("vale_lore:quest_progress", {
    player = player,
    quest = id,
    objective = key,
    count = after,
    goal = objective.goal,
  })
  if all_met(quest, entry) then
    finish(player, id, quest, entry)
  end
  return true
end

--- Completes a quest at once, every objective met: for a script that decides it some other
--- way. `false` when it isn't active for them.
---@param player Player
---@param id string
---@return boolean
function quests.complete(player, id)
  local quest = definition(id)
  local entry = entries(player)[id]
  if not entry or entry.status ~= "active" then
    return false
  end
  for key, objective in pairs(quest.objectives) do
    entry.progress[key] = objective.goal
  end
  finish(player, id, quest, entry)
  return true
end

--- What the journal shows: a player's quests, the active ones first, each with its objectives
--- in order, as MiniMessage lines.
---@param player Player
---@return string
function quests.journal_text(player)
  local all = entries(player)
  local active, done = {}, {}
  for _, id in ipairs(defined_order) do
    local entry = all[id]
    if entry then
      local list = entry.status == "done" and done or active
      list[#list + 1] = id
    end
  end
  if #active == 0 and #done == 0 then
    return "<gray>No quests yet. Someone in the world will have something to ask of you."
  end
  local lines = {}
  for _, id in ipairs(active) do
    local quest = definitions[id]
    lines[#lines + 1] = "<gold>" .. quest.title
    if quest.summary then
      lines[#lines + 1] = "<gray>" .. quest.summary
    end
    for _, key in ipairs(quest.order) do
      local objective = quest.objectives[key]
      local count = all[id].progress[key] or 0
      local mark = count >= objective.goal and "<green>✔" or "<yellow>•"
      lines[#lines + 1] = ("%s <white>%s <gray>%d/%d"):format(
        mark,
        objective.label,
        count,
        objective.goal
      )
    end
    lines[#lines + 1] = ""
  end
  for _, id in ipairs(done) do
    lines[#lines + 1] = "<green>✔ <gray>" .. definitions[id].title
  end
  return table.concat(lines, "\n")
end

--- Opens the journal (the library's `journal` dialog) on a player's screen, showing their quests.
---@param player Player
---@return boolean
function quests.open_journal(player)
  local opened = player:open_dialog("journal", { body = { entries = quests.journal_text(player) } })
  return opened
end

--- A player's ledger: every quest they started and finished, oldest first, as rows of
--- `{ quest, title, event, at }` (`event` is `"started"` or `"completed"`, `at` Unix time in
--- milliseconds), or `nil` and why not. It reads the database, so it waits: only in a task.
---@param player Player
---@return table[]?
---@return string?
function quests.history(player)
  local rows, err = db:query(
    "SELECT quest, event, at FROM quest_log WHERE player = ? ORDER BY at, id",
    { player:id() }
  )
  if not rows then
    return nil, err
  end
  for _, row in ipairs(rows) do
    local quest = definitions[row.quest]
    row.title = quest and quest.title or row.quest
  end
  return rows, nil
end

return quests
