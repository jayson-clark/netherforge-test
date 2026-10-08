package dev.netherforge.format.terrain

/**
 * The Lua a terrain script's state runs before the script: the sandbox, the budget and the API the script
 * sees (`terrain`, chunks, noises, a seeded `math.random`, `require` of the project's modules). It's one text for
 * every platform ([dev.netherforge.format.lua.LuaPlatform]), so a script behaves the same on the server and in the
 * editor's preview: only the host functions it's given (`host_*`, the Kotlin of [TerrainScriptState]) and the
 * `nf_*` entry points Kotlin calls cross between them. Scripts never see either: they run in an environment of
 * their own, without `debug`, `load`, `io`, `os`, `collectgarbage` or `nf`.
 *
 * `packages/api/src/terrain/` is the API's spec, which its LuaLS stubs and reference page are generated from;
 * `TerrainScriptApiTest` holds this text to it.
 */
internal object TerrainScriptGlue {
    const val CHUNK_NAME = "=nf/terrain"

    val SOURCE: String =
        """
local host_noise, host_fill, host_block = host_noise, host_fill, host_block
local host_file_height, host_area, host_resolve = host_file_height, host_area, host_resolve
local host_point_area, host_set_loot = host_point_area, host_set_loot
local host_module_path, host_load = host_module_path, host_load
for _, name in ipairs({
  "noise", "fill", "block", "file_height", "area", "point_area", "set_loot", "resolve", "module_path", "load",
}) do
  _G["host_" .. name] = nil
end

local debug_sethook, debug_getinfo, debug_setupvalue = debug.sethook, debug.getinfo, debug.setupvalue
local collect = collectgarbage
local error, pcall, type, tostring, tonumber, next = error, pcall, type, tostring, tonumber, next
local setmetatable, getmetatable, rawget = setmetatable, getmetatable, rawget
local tointeger, floor, huge = math.tointeger, math.floor, math.huge
local random, randomseed = math.random, math.randomseed
local create, resume = coroutine.create, coroutine.resume
local pack, unpack, sort, concat = table.pack, table.unpack, table.sort, table.concat
local sub, format, gmatch, rep, byte = string.sub, string.format, string.gmatch, string.rep, string.byte

local HOOK_EVERY = 1000
local MAX_STRING = 16 * 1024 * 1024

-- ---- the budget: instructions and memory, for every call in ------------------------------------------------

local budget, memory_kb, memory_mb = 0, 0, 0
local used, step = 0, HOOK_EVERY
local failure = nil
local check_memory = false

-- "file:line: " of the first frame at or above [level] (from the caller) that's a script's.
local function script_line(level)
  level = level + 1
  while true do
    local info = debug_getinfo(level, "Sl")
    if info == nil then
      return ""
    end
    if sub(info.source, 1, 1) == "@" and info.currentline > 0 then
      return info.short_src .. ":" .. info.currentline .. ": "
    end
    level = level + 1
  end
end

-- The one hook: it counts what a call has run, and once that's past its budget (or memory past its limit) every
-- later count fails it again, so a pcall in the script can't run on.
local function hook()
  used = used + step
  if failure == nil then
    if used > budget then
      failure = script_line(2) .. "ran past its budget of " .. budget .. " instructions"
    elseif collect("count") > memory_kb then
      failure = script_line(2) .. "used more than " .. memory_mb .. " MB of memory"
    elseif check_memory then
      check_memory = false
      step = HOOK_EVERY
      debug_sethook(hook, "", HOOK_EVERY)
    end
  end
  if failure ~= nil then
    error(failure, 0)
  end
end

-- A string doubled with `..` outgrows any limit between two counts, but a collection runs while it grows: at the
-- end of each cycle this asks the very next instruction to look at the memory (collectgarbage can't, in here).
local sentinel
sentinel = function()
  setmetatable({}, {
    __gc = function()
      check_memory = true
      step = 1
      debug_sethook(hook, "", 1)
      sentinel()
    end,
  })
end

-- ---- the sandbox ---------------------------------------------------------------------------------------

-- One call of string.rep can't make more than MAX_STRING bytes: it's one instruction, however long it takes.
string.rep = function(s, n, separator)
  if type(n) == "number" and n > 1 and (type(s) == "string" or type(s) == "number") then
    local size = #tostring(s) * n
    if separator ~= nil then
      size = size + #tostring(separator) * (n - 1)
    end
    if size > MAX_STRING then
      error("string.rep would make more than 16 MB", 2)
    end
  end
  return rep(s, n, separator)
end
getmetatable("").__metatable = false

local function copy(t)
  local out = {}
  for key, value in next, t do
    out[key] = value
  end
  return out
end

local env = {}
for _, name in ipairs({
  "assert", "error", "ipairs", "next", "pairs", "pcall", "rawequal", "rawget", "rawlen", "rawset", "select",
  "tonumber", "tostring", "type", "xpcall", "getmetatable",
}) do
  env[name] = _G[name]
end
env._VERSION = _VERSION
env._G = env
env.setmetatable = function(t, metatable)
  if type(metatable) == "table" and rawget(metatable, "__gc") ~= nil then
    error("a terrain script can't set __gc", 2)
  end
  return setmetatable(t, metatable)
end
env.string = copy(string)
env.string.dump = nil
env.table = copy(table)
env.utf8 = copy(utf8)

-- math.random is seeded for each call in from the world's seed and where it is (the chunk, the column), the
-- first time it's asked, so the same place always gets the same numbers.
-- A plan is made with numbers of its own (current_rng), whenever it's first asked for, so it doesn't move the
-- numbers of the stage that asked.
local call_seed, seeded = 0, true
local current_rng = nil
env.math = copy(math)
env.math.randomseed = nil
env.math.random = function(...)
  if current_rng ~= nil then
    return current_rng(...)
  end
  if not seeded then
    randomseed(call_seed)
    seeded = true
  end
  return random(...)
end

-- A coroutine is counted like the call that made it.
env.coroutine = copy(coroutine)
env.coroutine.create = function(body)
  local thread = create(body)
  debug_sethook(thread, hook, "", HOOK_EVERY)
  return thread
end
env.coroutine.wrap = function(body)
  local thread = env.coroutine.create(body)
  return function(...)
    local results = pack(resume(thread, ...))
    if not results[1] then
      error(results[2], 0)
    end
    return unpack(results, 2, results.n)
  end
end

-- `require` of the project's modules, in this script's environment: the requiring module's own files first
-- ("noise" in modules/terrain/ is modules/terrain/noise.lua), then a module by name ("terrain" is its init.lua,
-- "terrain.noise" its noise.lua).
local LOADING = {}
local loaded = {}
env.require = function(name)
  if type(name) ~= "string" then
    error(format("bad argument #1 to 'require' (a module's name expected, got %s)", type(name)), 2)
  end
  local caller = debug_getinfo(2, "S")
  local path = host_module_path(name, caller and caller.source or "")
  if path == nil then
    error(
      format(
        "no module \"%s\": a terrain's script requires the project's own modules, like \"terrain\" "
          .. "(modules/terrain/init.lua) or \"terrain.noise\" (modules/terrain/noise.lua)",
        name
      ),
      2
    )
  end
  local value = loaded[path]
  if value == LOADING then
    error(format("\"%s\" requires itself: %s is still loading", name, path), 2)
  end
  if value ~= nil then
    return value
  end
  local body = host_load(path)
  debug_setupvalue(body, 1, env)
  loaded[path] = LOADING
  local ok, result = pcall(body, name)
  if not ok then
    loaded[path] = nil
    error(result, 0)
  end
  if result == nil then
    result = true
  end
  loaded[path] = result
  return result
end

-- ---- checking what scripts pass ----------------------------------------------------------------------------

local function name_of(value)
  local kind = type(value)
  if kind == "number" and tointeger(value) == nil then
    return "a number with a fraction"
  end
  return kind
end

local function want_number(value, position, function_name)
  if type(value) ~= "number" then
    error(format("bad argument #%d to '%s' (number expected, got %s)", position, function_name, name_of(value)), 3)
  end
  return value
end

local function want_integer(value, position, function_name)
  local integer = type(value) == "number" and tointeger(value) or nil
  if integer == nil then
    error(format("bad argument #%d to '%s' (whole number expected, got %s)", position, function_name, name_of(value)), 3)
  end
  return integer
end

local function lines(text)
  local out = {}
  if text ~= "" then
    for line in gmatch(text, "[^\n]+") do
      out[#out + 1] = line
    end
  end
  return out
end

-- ---- blocks ------------------------------------------------------------------------------------------------

local labels = {}
local block_ids = {}

local function block_index(block, position, function_name)
  if type(block) ~= "string" then
    error(format("bad argument #%d to '%s' (block id expected, got %s)", position, function_name, name_of(block)), 3)
  end
  local index = block_ids[block]
  if index == nil then
    index = host_resolve(block)
    if index < 0 then
      error(
        format(
          "\"%s\" isn't a block this generator places: name the game's in full (\"minecraft:stone\"), the project's "
            .. "by id, and list any the file doesn't use in its script.blocks or script.customBlocks",
          block
        ),
        3
      )
    end
    block_ids[block] = index
  end
  return index
end

-- ---- the API -------------------------------------------------------------------------------------------------

local stages = nil
local seed, min_y, max_y, sea_level = 0, 0, 0, 0
local area_names, biome_names, area_index, volume_set = {}, {}, {}, {}
local loot_index = {}
local in_height = false
-- The stage Kotlin called in ("load", "height", "area"...), while it runs.
local running = nil

local Noise = { __metatable = false }
Noise.__index = Noise
local noise_index = setmetatable({}, { __mode = "k" })
local noises = {}

function Noise.at(self, x, y, z)
  local index = noise_index[self]
  if index == nil then
    error("call at with ':' on a noise: noise:at(x, z)", 2)
  end
  want_number(x, 1, "at")
  want_number(y, 2, "at")
  if z == nil then
    return host_noise(index, x, y)
  end
  return host_noise(index, x, y, want_number(z, 3, "at"))
end

local Chunk = { __metatable = false }
Chunk.__index = Chunk
local chunk = setmetatable({}, Chunk)
local chunk_x, chunk_z, in_chunk = 0, 0, false

local function want_chunk(self, function_name)
  if self ~= chunk then
    error(format("call %s with ':' on the chunk a stage is given: chunk:%s(...)", function_name, function_name), 3)
  end
  if not in_chunk then
    error("a chunk can only be used during the stage it's given to", 3)
  end
end

function Chunk.x(self)
  want_chunk(self, "x")
  return chunk_x
end

function Chunk.z(self)
  want_chunk(self, "z")
  return chunk_z
end

function Chunk.min_x(self)
  want_chunk(self, "min_x")
  return chunk_x * 16
end

function Chunk.min_z(self)
  want_chunk(self, "min_z")
  return chunk_z * 16
end

function Chunk.fill(self, x1, y1, z1, x2, y2, z2, block)
  want_chunk(self, "fill")
  host_fill(
    want_integer(x1, 1, "fill"),
    want_integer(y1, 2, "fill"),
    want_integer(z1, 3, "fill"),
    want_integer(x2, 4, "fill"),
    want_integer(y2, 5, "fill"),
    want_integer(z2, 6, "fill"),
    block_index(block, 7, "fill")
  )
end

function Chunk.set(self, x, y, z, block)
  want_chunk(self, "set")
  x, y, z = want_integer(x, 1, "set"), want_integer(y, 2, "set"), want_integer(z, 3, "set")
  host_fill(x, y, z, x, y, z, block_index(block, 4, "set"))
end

function Chunk.set_loot(self, x, y, z, table_name)
  want_chunk(self, "set_loot")
  x, y, z = want_integer(x, 1, "set_loot"), want_integer(y, 2, "set_loot"), want_integer(z, 3, "set_loot")
  if type(table_name) ~= "string" then
    error(format("bad argument #4 to 'set_loot' (loot table expected, got %s)", name_of(table_name)), 2)
  end
  local index = loot_index[table_name]
  if index == nil then
    error(format("\"%s\" isn't a loot table this generator fills: list it in the file's script.loot", table_name), 2)
  end
  host_set_loot(x, y, z, index)
end

function Chunk.block(self, x, y, z)
  want_chunk(self, "block")
  local index = host_block(want_integer(x, 1, "block"), want_integer(y, 2, "block"), want_integer(z, 3, "block"))
  if index < 0 then
    return nil
  end
  return labels[index]
end

local function clamp(height)
  if height < min_y then
    return min_y
  end
  if height > max_y - 1 then
    return max_y - 1
  end
  return height
end

local function finite(value)
  return type(value) == "number" and value == value and value ~= huge and value ~= -huge
end

local terrain = {}

function terrain.seed()
  return seed
end

function terrain.min_y()
  return min_y
end

function terrain.max_y()
  return max_y
end

function terrain.sea_level()
  return sea_level
end

function terrain.noise(name)
  if type(name) ~= "string" then
    error(format("bad argument #1 to 'noise' (a noise's name expected, got %s)", name_of(name)), 2)
  end
  local noise = noises[name]
  if noise == nil then
    error(format("\"%s\" isn't one of the noises the file's script.noises declares", name), 2)
  end
  return noise
end

-- A column's height as a chunk is generated with it: the file's, then this script's height stage. One that fails
-- (or gives no number) leaves the file's, as the column itself does.
function terrain.height(x, z)
  x, z = want_integer(x, 1, "height"), want_integer(z, 2, "height")
  if in_height then
    error("terrain.height can't be asked from the height stage: use the height the stage is given", 2)
  end
  if running == "area" then
    error("terrain.height can't be asked from the area stage: a column's height depends on the areas", 2)
  end
  local height = tointeger(host_file_height(x, z))
  local stage = stages and stages.height
  if stage ~= nil then
    in_height = true
    local ok, result = pcall(stage, x, z, height)
    in_height = false
    if ok and finite(result) then
      height = floor(result)
    end
  end
  return clamp(height)
end

-- The area of a column (x, z), or with three numbers of a place (x, y, z), as an index in the file's areas.
local function area_of(x, y, z, function_name)
  if running == "area" then
    error(format("terrain.%s can't be asked from the area stage: use the area the stage is given", function_name), 3)
  end
  if z == nil then
    return host_area(want_integer(x, 1, function_name), want_integer(y, 2, function_name))
  end
  if in_height or running == "height" or running == "density" or running == "biome" then
    error(
      format(
        "terrain.%s of a place (x, y, z) can't be asked from the %s stage: a place's area depends on the heights",
        function_name,
        running == "biome" and "biome" or (running == "density" and "density" or "height")
      ),
      3
    )
  end
  return host_point_area(want_integer(x, 1, function_name), want_integer(y, 2, function_name), want_integer(z, 3, function_name))
end

function terrain.area(x, y, z)
  return area_names[area_of(x, y, z, "area") + 1]
end

function terrain.biome(x, y, z)
  return biome_names[area_of(x, y, z, "biome") + 1]
end

-- ---- plans: what a big structure is, worked out once per cell rather than in every chunk it touches ----------

-- 64-bit mixing (splitmix64's), the same in every Lua 5.4: integers wrap.
local function mix64(z)
  z = (z ~ (z >> 30)) * 0xbf58476d1ce4e5b9
  z = (z ~ (z >> 27)) * 0x94d049bb133111eb
  return z ~ (z >> 31)
end

local function hash_name(text)
  local h = 0xcbf29ce484222325
  for i = 1, #text do
    h = (h ~ byte(text, i)) * 0x100000001b3
  end
  return h
end

-- math.random's three forms, from numbers of the plan's own.
local function make_random(state)
  return function(m, n)
    state = state + 0x9e3779b97f4a7c15
    local r = mix64(state)
    if m == nil then
      return (r >> 11) * (1.0 / 9007199254740992.0)
    end
    m = want_integer(m, 1, "random")
    if n == nil then
      m, n = 1, m
    else
      n = want_integer(n, 2, "random")
    end
    local span = n - m + 1
    if span <= 0 then
      error("bad argument to 'random' (interval is empty)", 2)
    end
    return m + (r >> 1) % span
  end
end

local PLAN_CACHE = 256
local NONE = {}
local Plan = { __metatable = false }
Plan.__index = Plan
local plan_data = setmetatable({}, { __mode = "k" })
local plans_by_name = {}

local function plan_of(self, function_name)
  local data = plan_data[self]
  if data == nil then
    error(format("call %s with ':' on a plan: plan:%s(...)", function_name, function_name), 3)
  end
  return data
end

local function plan_cell(data, cell_x, cell_z)
  local key = cell_x .. "," .. cell_z
  local cached = data.cache[key]
  if cached ~= nil then
    if cached == NONE then
      return nil
    end
    return cached
  end
  local saved = current_rng
  current_rng = make_random(mix64(data.seed ~ mix64(cell_x * 0x1B873593) ~ mix64(cell_z * 0x2C1B3C6D)))
  local ok, result = pcall(data.make, cell_x, cell_z)
  current_rng = saved
  if not ok then
    error(result, 0)
  end
  if data.count >= PLAN_CACHE then
    data.cache, data.count = {}, 0
  end
  data.cache[key] = result == nil and NONE or result
  data.count = data.count + 1
  return result
end

function Plan.get(self, cell_x, cell_z)
  local data = plan_of(self, "get")
  return plan_cell(data, want_integer(cell_x, 1, "get"), want_integer(cell_z, 2, "get"))
end

function Plan.at(self, x, z)
  local data = plan_of(self, "at")
  local cell_x, cell_z = want_integer(x, 1, "at") // data.size, want_integer(z, 2, "at") // data.size
  return plan_cell(data, cell_x, cell_z), cell_x, cell_z
end

function Plan.size(self)
  return plan_of(self, "size").size
end

function terrain.plan(name, size, make)
  if running ~= "load" then
    error("terrain.plan is made in the script's body, once, not in a stage", 2)
  end
  if type(name) ~= "string" or name == "" then
    error(format("bad argument #1 to 'plan' (a plan's name expected, got %s)", name_of(name)), 2)
  end
  if plans_by_name[name] then
    error(format("there's already a plan called \"%s\"", name), 2)
  end
  size = want_integer(size, 2, "plan")
  if size < 1 or size > 65536 then
    error(format("bad argument #2 to 'plan' (a cell's size is from 1 to 65536 blocks, not %d)", size), 2)
  end
  if type(make) ~= "function" then
    error(format("bad argument #3 to 'plan' (function expected, got %s)", name_of(make)), 2)
  end
  local plan = setmetatable({}, Plan)
  plan_data[plan] = { size = size, make = make, seed = mix64(seed ~ hash_name(name)), cache = {}, count = 0 }
  plans_by_name[name] = true
  return plan
end

-- ---- what Kotlin calls --------------------------------------------------------------------------------------
-- Kotlin passes whole numbers as plain numbers (a 64-bit integer is slow to make in JS), so each is made an
-- integer here before a script sees it; a height goes back as a float for the same reason.

local function begin(seed_of_call, stage)
  used, step, failure, check_memory = 0, HOOK_EVERY, nil, false
  call_seed, seeded, current_rng = tointeger(seed_of_call), false, nil
  running = stage
  debug_sethook(hook, "", HOOK_EVERY)
end

local function finish(ok, result)
  debug_sethook()
  in_chunk, in_height, running, current_rng = false, false, nil, nil
  if failure ~= nil then
    error(failure, 0)
  end
  if not ok then
    error(type(result) == "string" and result or "an error that isn't a message: " .. tostring(result), 0)
  end
  return result
end

local STAGES = { height = true, density = true, area = true, biome = true, terrain = true, decorate = true }

-- Loads the script at [path] and runs its body; answers the stages it has, "height,terrain" (sorted).
function nf_load(path, budget_of_call, memory, seed_text, lowest, highest, sea, noise_names, areas, biomes, palette, volumes, loot)
  budget, memory_mb = tointeger(budget_of_call), tointeger(memory)
  memory_kb = memory_mb * 1024
  seed, min_y, max_y, sea_level = tonumber(seed_text), tointeger(lowest), tointeger(highest), tointeger(sea)
  area_names, biome_names = lines(areas), lines(biomes)
  for index, name in ipairs(area_names) do
    area_index[name] = index - 1
  end
  for _, name in ipairs(lines(volumes)) do
    volume_set[name] = true
  end
  for index, name in ipairs(lines(loot)) do
    loot_index[name] = index - 1
  end
  for index, label in ipairs(lines(palette)) do
    labels[index - 1] = label
  end
  for index, name in ipairs(lines(noise_names)) do
    local noise = setmetatable({}, Noise)
    noise_index[noise] = index - 1
    noises[name] = noise
  end
  sentinel()
  local body = host_load(path)
  debug_setupvalue(body, 1, env)
  begin(0, "load")
  local ok, result = pcall(body, terrain)
  result = finish(ok, result)
  if type(result) ~= "table" then
    error(
      path .. ": the script must return a table of its stages (height, density, area, biome, terrain, decorate), not " .. type(result),
      0
    )
  end
  local names = {}
  for key, value in next, result do
    if not STAGES[key] then
      error(
        path .. ": \"" .. tostring(key) .. "\" isn't a stage: a script's stages are height, density, area, biome, terrain and decorate",
        0
      )
    end
    if type(value) ~= "function" then
      error(path .. ": the stage " .. key .. " must be a function, not " .. type(value), 0)
    end
    names[#names + 1] = key
  end
  sort(names)
  stages = {
    height = rawget(result, "height"),
    density = rawget(result, "density"),
    area = rawget(result, "area"),
    biome = rawget(result, "biome"),
    terrain = rawget(result, "terrain"),
    decorate = rawget(result, "decorate"),
  }
  return concat(names, ",")
end

-- The height of column ([x], [z]) from the file's [height]: a whole number, as a float.
function nf_height(x, z, height, seed_of_call)
  begin(seed_of_call, "height")
  in_height = true
  local ok, result = pcall(stages.height, tointeger(x), tointeger(z), tointeger(height))
  result = finish(ok, result)
  if not finite(result) then
    local info = debug_getinfo(stages.height, "S")
    error(format("%s:%d: the height stage must return a number, not %s", info.short_src, info.linedefined, name_of(result)), 0)
  end
  return floor(result) + 0.0
end

-- The density at the grid's point ([x], [y], [z]) from the file's [value] there: a float.
function nf_density(x, y, z, value, seed_of_call)
  begin(seed_of_call, "density")
  local ok, result = pcall(stages.density, tointeger(x), tointeger(y), tointeger(z), value + 0.0)
  result = finish(ok, result)
  if not finite(result) then
    local info = debug_getinfo(stages.density, "S")
    error(format("%s:%d: the density stage must return a number, not %s", info.short_src, info.linedefined, name_of(result)), 0)
  end
  return result + 0.0
end

-- What an area stage returned, as an index in the file's areas: [columns] when it must be a column's own.
local function area_result(stage, result, columns)
  local index = type(result) == "string" and area_index[result] or nil
  if index == nil or (columns and volume_set[result]) then
    local info = debug_getinfo(stage, "S")
    local given = type(result) == "string" and format("\"%s\"", result) or name_of(result)
    local which = columns and "one of the file's biome areas that isn't limited by height" or "one of the file's biome areas"
    error(format("%s:%d: the %s stage must return the name of %s, not %s", info.short_src, info.linedefined,
      columns and "area" or "biome", which, given), 0)
  end
  return index + 0.0
end

-- The area of column ([x], [z]) from the file's (an index): an index, as a float.
function nf_area(x, z, file, seed_of_call)
  begin(seed_of_call, "area")
  local ok, result = pcall(stages.area, tointeger(x), tointeger(z), area_names[tointeger(file) + 1])
  return area_result(stages.area, finish(ok, result), true)
end

-- The area of the place ([x], [y], [z]) from the file's: an index, as a float.
function nf_biome(x, y, z, file, seed_of_call)
  begin(seed_of_call, "biome")
  local ok, result = pcall(stages.biome, tointeger(x), tointeger(y), tointeger(z), area_names[tointeger(file) + 1])
  return area_result(stages.biome, finish(ok, result), false)
end

-- Runs the chunk stage [stage] on chunk ([x], [z]).
function nf_chunk(stage, x, z, seed_of_call)
  begin(seed_of_call, stage)
  chunk_x, chunk_z, in_chunk = tointeger(x), tointeger(z), true
  finish(pcall(stages[stage], chunk))
end
"""
}
