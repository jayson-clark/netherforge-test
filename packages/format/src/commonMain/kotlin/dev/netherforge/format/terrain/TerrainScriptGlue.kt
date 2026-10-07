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
local host_module_path, host_load = host_module_path, host_load
for _, name in ipairs({ "noise", "fill", "block", "file_height", "area", "resolve", "module_path", "load" }) do
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
local sub, format, gmatch, rep = string.sub, string.format, string.gmatch, string.rep

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
local call_seed, seeded = 0, true
env.math = copy(math)
env.math.randomseed = nil
env.math.random = function(...)
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
local area_names, biome_names = {}, {}
local in_height = false

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

function terrain.area(x, z)
  return area_names[host_area(want_integer(x, 1, "area"), want_integer(z, 2, "area")) + 1]
end

function terrain.biome(x, z)
  return biome_names[host_area(want_integer(x, 1, "biome"), want_integer(z, 2, "biome")) + 1]
end

-- ---- what Kotlin calls --------------------------------------------------------------------------------------
-- Kotlin passes whole numbers as plain numbers (a 64-bit integer is slow to make in JS), so each is made an
-- integer here before a script sees it; a height goes back as a float for the same reason.

local function begin(seed_of_call)
  used, step, failure, check_memory = 0, HOOK_EVERY, nil, false
  call_seed, seeded = tointeger(seed_of_call), false
  debug_sethook(hook, "", HOOK_EVERY)
end

local function finish(ok, result)
  debug_sethook()
  in_chunk, in_height = false, false
  if failure ~= nil then
    error(failure, 0)
  end
  if not ok then
    error(type(result) == "string" and result or "an error that isn't a message: " .. tostring(result), 0)
  end
  return result
end

local STAGES = { height = true, density = true, terrain = true, decorate = true }

-- Loads the script at [path] and runs its body; answers the stages it has, "height,terrain" (sorted).
function nf_load(path, budget_of_call, memory, seed_text, lowest, highest, sea, noise_names, areas, biomes, palette)
  budget, memory_mb = tointeger(budget_of_call), tointeger(memory)
  memory_kb = memory_mb * 1024
  seed, min_y, max_y, sea_level = tonumber(seed_text), tointeger(lowest), tointeger(highest), tointeger(sea)
  area_names, biome_names = lines(areas), lines(biomes)
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
  begin(0)
  local ok, result = pcall(body, terrain)
  result = finish(ok, result)
  if type(result) ~= "table" then
    error(path .. ": the script must return a table of its stages (height, density, terrain, decorate), not " .. type(result), 0)
  end
  local names = {}
  for key, value in next, result do
    if not STAGES[key] then
      error(path .. ": \"" .. tostring(key) .. "\" isn't a stage: a script's stages are height, density, terrain and decorate", 0)
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
    terrain = rawget(result, "terrain"),
    decorate = rawget(result, "decorate"),
  }
  return concat(names, ",")
end

-- The height of column ([x], [z]) from the file's [height]: a whole number, as a float.
function nf_height(x, z, height, seed_of_call)
  begin(seed_of_call)
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
  begin(seed_of_call)
  local ok, result = pcall(stages.density, tointeger(x), tointeger(y), tointeger(z), value + 0.0)
  result = finish(ok, result)
  if not finite(result) then
    local info = debug_getinfo(stages.density, "S")
    error(format("%s:%d: the density stage must return a number, not %s", info.short_src, info.linedefined, name_of(result)), 0)
  end
  return result + 0.0
end

-- Runs the chunk stage [stage] on chunk ([x], [z]).
function nf_chunk(stage, x, z, seed_of_call)
  begin(seed_of_call)
  chunk_x, chunk_z, in_chunk = tointeger(x), tointeger(z), true
  finish(pcall(stages[stage], chunk))
end
"""
}
