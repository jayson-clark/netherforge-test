local this = this --[[@as Dialog]]

-- The journal's pages. The dialog holds nothing itself: each opening is given its text (the
-- `entries` body element) by whoever opens it, so these buttons open it again with another page.
local quests = require("quests")

this:button("quests"):on("press", function(event)
  quests.open_journal(event.player)
end)

-- The ledger is in the library's database, so reading it waits: a handler can't, a task can.
this:button("history"):on("press", function(event)
  local player = event.player
  nf.task(function()
    local rows, err = quests.history(player)
    local lines = {}
    if not rows then
      lines[1] = "<red>The pages are smudged: " .. nf.text.escape(err or "unknown")
    elseif #rows == 0 then
      lines[1] = "<gray>Nothing yet."
    end
    for _, row in ipairs(rows or {}) do
      local when = nf.time.format(row.at, "dd MMM HH:mm")
      local verb = row.event == "completed" and "<green>finished" or "<yellow>began"
      lines[#lines + 1] = ("<dark_gray>%s</dark_gray> %s <white>%s"):format(when, verb, row.title)
    end
    player:open_dialog("journal", {
      title = "<gold>Past deeds",
      body = { entries = table.concat(lines, "\n") },
    })
  end)
end)
