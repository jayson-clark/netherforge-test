local this = this --[[@as Dialog]]

-- The keeper's conversation. There's one of this dialog, shown afresh every time: each button
-- answers by opening it again with what the keeper says now (its `speech` body element).
local keeper = require("keeper")
-- The library's journal: quests.open_journal opens vale_lore's own `journal` dialog.
local quests = require("vale_lore:quests")

this:button("ask"):on("press", function(event)
  -- A single_option input's answer is the id of the option picked.
  keeper.converse(event.player, keeper.answer(tostring(event.values.topic)))
end)

this:button("offer"):on("press", function(event)
  keeper.converse(event.player, keeper.offer(event.player))
end)

this:button("journal"):on("press", function(event)
  quests.open_journal(event.player)
end)
