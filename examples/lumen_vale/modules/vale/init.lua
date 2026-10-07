-- /vale: a player's way into the project's state, and a few tools for whoever runs the server.
local quests = require("vale_lore:quests")
local discovery = require("discovery")
local keeper = require("keeper")
local sky_reach = require("sky_reach")

nf.commands.register("vale", {
  description = "How far you are in the vale",
  players_only = true,
  subcommands = {
    journal = {
      description = "Open your journal",
      handler = function(event)
        quests.open_journal(assert(event.player))
      end,
    },
    places = {
      description = "The places in the vale you've found",
      handler = function(event)
        local player = assert(event.player)
        nf.task(function()
          local rows, err = discovery.places(player)
          if not rows then
            player:send_message(
              "<red>Couldn't read your travels: " .. nf.text.escape(err or "unknown")
            )
            return
          end
          local lines = { "<light_purple><glyph:lumen/lumen> Places you've found" }
          for _, row in ipairs(rows) do
            lines[#lines + 1] = ("%s <dark_gray>%s"):format(
              discovery.NAMES[row.place] or row.place,
              nf.time.format(row.at, "dd MMM HH:mm")
            )
          end
          if #rows == 0 then
            lines[#lines + 1] = "<gray>Nowhere yet. Go for a walk."
          end
          player:send_message(table.concat(lines, "\n"))
        end)
      end,
    },
    -- For whoever runs the server: lumen_vale.admin is nobody's unless granted, so on Paper
    -- operators and the console have it.
    altar = {
      description = "Open a lumen altar where you stand",
      permission = "lumen_vale.admin",
      handler = function(event)
        assert(event.player):open_menu("lumen_altar")
      end,
    },
    sky = {
      description = "Go up to the sky reach, or back down",
      permission = "lumen_vale.admin",
      handler = function(event)
        local player = assert(event.player)
        local location = player:location()
        if location and location.world:name() == sky_reach.WORLD then
          sky_reach.descend(player)
        else
          sky_reach.ascend(player)
        end
      end,
    },
  },
}, function(event)
  local player = assert(event.player)
  local status = quests.status(player, keeper.QUEST)
  local where = {
    none = "<gray>Find the shrine keeper in the crystal grove.",
    active = "<yellow>The keeper's quest is under way: <white>/vale journal",
    done = "<green>The keeper's quest is done. Kindle your lantern at an altar.",
  }
  player:send_message(
    "<light_purple><glyph:lumen/wisp> Lumen Vale</light_purple>\n" .. where[status]
  )
end)
