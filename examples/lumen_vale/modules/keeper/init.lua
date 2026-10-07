-- The shrine keeper: the old spirit who tends the ruined shrines of the crystal grove, and the
-- quest they set. The quest is kept by the vale_lore library (its exported quests module):
-- this module defines it and says what talking to the keeper does. The keeper's centity
-- (centities/shrine_keeper) and dialog (dialogs/keeper) both call in here.
local quests = require("vale_lore:quests")

local keeper = {}

--- The keeper's quest, by the id it has in the library (shared by every package, so ours).
keeper.QUEST = "lumen_vale:rekindle"

-- How many shards the keeper asks for is the server owner's to set (netherforge.json's
-- settings). This module reads it without listening for setting_changed, so a change restarts
-- the module, and the quest is defined again with the new number.
local SHARDS = nf.config("keeper_shards")
local WISPS = 3

quests.define(keeper.QUEST, {
  title = "<light_purple>Rekindle the Vale",
  summary = "The shrine keeper asks for lumen, and for the wisps' peace.",
  objectives = {
    shards = { label = "Lumen shards offered", goal = SHARDS },
    wisps = { label = "Wisps calmed", goal = WISPS },
  },
  order = { "shards", "wisps" },
  rewards = { items = { nf.items.create("lumen_lantern") }, experience = 30 },
  on_complete = function(player)
    player:send_message(
      "<light_purple><glyph:lumen/wisp> The keeper hands you a cold lantern. "
        .. "<gray>Kindle it at a lumen altar, then raise it at a shrine."
    )
  end,
})

-- What the keeper says about each of the dialog's topics (its `topic` input's options).
local ANSWERS = {
  vale = "Lumen grows through the stone of this vale like roots through soil. "
    .. "Where it runs close to the surface, the grove turns violet and the spirits wake.",
  wisps = "The wisps are what's left of the vale's old light. They drift out at night. "
    .. "Hold out your hand to one, gently, and it will settle.",
  altar = "Glass, lumen and a wisp's essence make a lamp, and every lamp is an altar. "
    .. "Set something in its ring and it will take the light.",
  sky = "Above the grove the old islands still float. A kindled lantern remembers the way: "
    .. "raise one here, at the shrine, and it will lift you.",
}

--- What the keeper says about a topic.
---@param topic string?
---@return string
function keeper.answer(topic)
  return "<gray>" .. (ANSWERS[topic or "vale"] or ANSWERS.vale)
end

--- What the keeper says to a player now, by how far they are with the quest.
---@param player Player
---@return string
function keeper.speech(player)
  local status = quests.status(player, keeper.QUEST)
  if status == "done" then
    return "<gray>The grove is brighter for you. Kindle the lantern I gave you at a lumen altar, "
      .. "then raise it here, and the sky reach will open."
  end
  return ("<gray>Bring me lumen, <white>%d</white> more shards, and calm <white>%d</white> more of the wisps. "):format(
    quests.remaining(player, keeper.QUEST, "shards"),
    quests.remaining(player, keeper.QUEST, "wisps")
  ) .. "Then the vale will remember the way up."
end

--- Opens the keeper's dialog for a player, saying `speech` (by default, what they'd say now).
---@param player Player
---@param speech string?
---@return boolean
function keeper.converse(player, speech)
  local opened =
    player:open_dialog("keeper", { body = { speech = speech or keeper.speech(player) } })
  return opened
end

--- A player greets the keeper: the first time, the keeper sets the quest; after that, says
--- how it's going.
---@param player Player
function keeper.greet(player)
  if quests.start(player, keeper.QUEST) then
    keeper.converse(
      player,
      "<gray>A visitor, after so long. The vale is dimming, and I am too old to mend it alone. "
        .. keeper.speech(player)
    )
    return
  end
  keeper.converse(player)
end

--- A player offers the keeper the lumen shards they carry: as many as the quest still needs.
--- Gives what the keeper says back.
---@param player Player
---@return string
function keeper.offer(player)
  if quests.status(player, keeper.QUEST) ~= "active" then
    return keeper.speech(player)
  end
  local wanted = quests.remaining(player, keeper.QUEST, "shards")
  if wanted == 0 then
    return "<gray>I have all the lumen I need. The wisps are what's left."
  end
  local given = player:inventory():remove_item({ item = "lumen_shard" }, wanted)
  if given == 0 then
    return "<gray>You carry no lumen. Look for it in the stone, where it glows."
  end
  quests.advance(player, keeper.QUEST, "shards", given)
  if quests.status(player, keeper.QUEST) == "done" then
    return "<light_purple>That's the last of it. Thank you, wanderer."
  end
  return ("<gray>%d shards. Warm still. "):format(given) .. keeper.speech(player)
end

return keeper
