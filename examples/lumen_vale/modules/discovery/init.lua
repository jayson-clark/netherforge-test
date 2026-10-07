-- Discovery: what happens as players walk into the vale's biomes. There's no event for entering
-- a biome, so this listens for moves (player_move: only moves into another block) and looks at
-- the biome there. Each biome's name is shown as a player enters it; the first time anyone sets
-- foot in the crystal grove, they're shown it: the grove's awakening (cutscenes/grove_awakening),
-- a flight over the grove to the nearest shrine.
--
-- "The first time" has to survive restarts and reloads, so it's kept in the project's own
-- database (migrations/001_discoveries.sql), one row per player and place.

local discovery = {}

local db = nf.db()

--- What each of the project's biomes is called when a player walks into it.
discovery.NAMES = {
  mossy_meadows = "<green>Mossy Meadows",
  crystal_grove = "<light_purple>Crystal Grove",
  ashen_ridge = "<gray>Ashen Ridge",
}

-- How far from a player the cutscene looks for a shrine to fly to.
local SHRINE_SEARCH = 160

-- Ticks between the first steps into the grove and the awakening: a moment to look round, and
-- for the chunks round them to load (a camera that starts in a chunk the server hasn't loaded
-- yet lets go of the player at once).
local AWAKENING_DELAY = 40

-- netherforge.json's `grove_cutscene` setting, kept up to date.
local awakening = nf.config("grove_cutscene")
nf.on("setting_changed", function(event)
  if event.setting == "grove_cutscene" then
    awakening = event.value
  end
end)

-- Which biome each player is in now, and which places this server has seen them find since
-- they joined (so walking about doesn't ask the database every step).
local current = {}
local found = {}

--- Plays the grove's awakening for a player: from the nearest shrine within reach, so the
--- flight ends at its keeper, or from where they stand when there's none.
---@param player Player
---@return Cutscene?
function discovery.awaken(player)
  local location = player:location()
  if not location then
    return nil
  end
  local keepers = nf.centities.all({
    kind = "shrine_keeper",
    world = location.world,
    near = location.position,
    radius = SHRINE_SEARCH,
  })
  local origin = location.position
  if keepers[1] then
    origin = keepers[1]:position() or origin
  end
  location.world:load_chunk(origin)
  local scene =
    nf.cutscenes.play(player, "grove_awakening", { origin = location.world:location(origin) })
  if not scene then
    return nil
  end
  scene:on("cue", function(cue)
    if cue.cue == "arrive" then
      cue.player:play_sound("minecraft:block.amethyst_block.chime", { volume = 0.8 })
    end
  end)
  scene:on("end", function(done)
    local text = keepers[1] and "Find the shrine keeper."
      or "Somewhere in the grove, a shrine waits."
    done.player:send_actionbar("<light_purple><glyph:lumen/wisp> <gray>" .. text)
  end)
  return scene
end

-- A place found for the first time (the database says so).
local function first_found(player, place)
  nf.emit("lumen_vale:discovered", { player = player, place = place })
  if place == "crystal_grove" and awakening then
    nf.after(AWAKENING_DELAY, function()
      if player:location() and not nf.cutscenes.current(player) then
        discovery.awaken(player)
      end
    end)
  end
end

--- Notes that a player is in a place. The first time they're anywhere (ever, by the
--- database), it's a discovery: `lumen_vale:discovered`, and the awakening for the grove.
---@param player Player
---@param place string
function discovery.visit(player, place)
  local id = player:id()
  found[id] = found[id] or {}
  if found[id][place] then
    return
  end
  found[id][place] = true
  -- One row per player and place: an insert that changes nothing means they'd been before.
  db:execute(
    "INSERT INTO discoveries (player, place, at) VALUES (?, ?, ?) ON CONFLICT (player, place) DO NOTHING",
    { id, place, nf.server.unix_time() },
    function(result, err)
      if err then
        log("couldn't note " .. player:name() .. " finding " .. place .. ": " .. err)
      elseif result and result.changes > 0 then
        first_found(player, place)
      end
    end
  )
end

--- A player walked into a biome.
---@param player Player
---@param biome string
function discovery.entered(player, biome)
  local name = discovery.NAMES[biome]
  if not name then
    return
  end
  player:send_title("", "<glyph:lumen/lumen> " .. name, { fade_in = 10, stay = 40, fade_out = 20 })
  discovery.visit(player, biome)
end

nf.on("player_move", function(event)
  local player = event.player
  -- A player watching a cutscene is moved along with its camera: that's not walking anywhere.
  if nf.cutscenes.current(player) then
    return
  end
  local block = event.to.world:block(event.to.position)
  local biome = block and block:biome()
  local id = player:id()
  if biome and biome ~= current[id] then
    current[id] = biome
    discovery.entered(player, biome)
  end
end)

nf.on("player_quit", function(event)
  local id = event.player:id()
  current[id] = nil
  found[id] = nil
end)

--- The places a player has found, oldest first, as `{ place, at }` rows (`at` in Unix
--- milliseconds), or `nil` and why not. It reads the database, so only in a task.
---@param player Player
---@return table[]?
---@return string?
function discovery.places(player)
  local rows, err = db:query(
    "SELECT place, at FROM discoveries WHERE player = ? ORDER BY at, rowid",
    { player:id() }
  )
  return rows, err
end

return discovery
