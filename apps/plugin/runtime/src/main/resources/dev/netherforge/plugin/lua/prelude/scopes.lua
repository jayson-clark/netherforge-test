-- Scopes: the environment each script runs in (`new_env`), its `nf`, `log` and
-- `vec3`, and `require`.
--
-- `require` reaches project modules and, from a resource's script, the
-- files beside it. The host resolves a name to a project path and to the
-- scope whose globals that file runs in: a module file runs in its module's
-- scope, a resource's own file in the requiring script's scope. A file runs
-- once per scope and its result is kept there, so every requirer of a module
-- gets the very table the running module holds, while each centity instance,
-- menu window and dialog gets its own copy of the files beside its script.

local std = require("std")
local input = require("input")
local args = require("args")
local gates = require("gates")
local values = require("values")
local sandbox = require("sandbox")
local guard_module = require("guard")
local events = require("events")
local api = require("api")
local schema = require("schema")

local prim = input.prim
local load, concat = std.load, std.concat
local xpcall, pairs, ipairs, error, select, tostring, setmetatable =
  xpcall, pairs, ipairs, error, select, tostring, setmetatable
local want = args.want
local gated, gate_functions = gates.gated, gates.gate_functions
local locate, project_frame, current =
  guard_module.locate, guard_module.project_frame, guard_module.current
local budgets, namespaces, nf_tables = events.budgets, events.namespaces, events.nf_tables

-- Each scope's global environment, by scope id.
---@type table<integer, table>
local envs = {}
-- By the scope a file runs in, then its path: `loaded` is what the file
-- returned, `loading` is true while it runs (a second require then is circular).
-- `drop_env` forgets a scope's.
local loaded = {}
local loading = {}

local function load_path(owner, path)
  local done = loaded[owner]
  local value = done and done[path]
  if value ~= nil then
    return value
  end
  local busy = loading[owner]
  if busy and busy[path] then
    error("circular require of " .. path, 0)
  end
  local env = envs[owner]
  if env == nil then
    error("module scope for " .. path .. " is gone", 0)
  end
  local chunk, problem = load(prim.source(path), "@" .. path, "t", env)
  if chunk == nil then
    error(problem, 0)
  end
  if busy == nil then
    busy = {}
    loading[owner] = busy
  end
  busy[path] = true
  -- The file runs as its scope's code, whoever required it.
  local f = current()
  local previous = f and f.scope
  if f ~= nil then
    f.scope = owner
  end
  local ok, result = xpcall(chunk, locate)
  if f ~= nil then
    f.scope = previous
  end
  busy[path] = nil
  if not ok then
    error(result, 0)
  end
  if result == nil then
    result = true
  end
  -- The scope may have closed while the file ran (it failed, or a reload): keep nothing for it then.
  if envs[owner] ~= nil then
    done = loaded[owner]
    if done == nil then
      done = {}
      loaded[owner] = done
    end
    done[path] = result
  end
  return result
end

local function make_require(scope)
  return function(name)
    want(name, "string", "name")
    local path, owner = prim.resolve(scope, name)
    return load_path(owner, path)
  end
end

local function log_line(...)
  local parts = {}
  for i = 1, select("#", ...) do
    parts[i] = tostring((select(i, ...)))
  end
  return concat(parts, "\t")
end

local function make_log(scope)
  return function(...)
    local file, line = project_frame(2)
    prim.log(scope, log_line(...), file, line)
  end
end

-- The table at a namespace's path in a scope's `nf`: "nf.worlds" is nf.worlds.
local function namespace(nf, path)
  local target = nf
  for part in std.raw.gmatch(path, "[^.]+", 4) do
    target = target[part]
  end
  return target
end

-- Whether a test run has the project (`nf.test` and what else the spec marks `testOnly`).
local testing = prim.testing()

-- Fills a scope's `nf`: the generated functions (which call the hand-written
-- bodies), the namespaces only a test run has (taken out again on a server),
-- then the version gates.
local function fill_nf(scope, nf)
  api.fill_nf(scope, nf)
  if not testing then
    for _, path in ipairs(schema.test_only) do
      local parent, name = std.raw.match(path, "^(.*)%.([^.]+)$")
      namespace(nf, parent)[name] = nil
    end
  end
  for path in pairs(gated.functions) do
    if path == "nf" or path:sub(1, 3) == "nf." then
      gate_functions(namespace(nf, path), path, ".")
    end
  end
end

local function make_nf(scope)
  local nf = {}
  fill_nf(scope, nf)
  nf_tables[nf] = true
  return nf
end

-- A scope's globals: the shared ones (`nf`, `require`, `log`, `print`, `vec3`), plus `this` on a
-- resource's script: the Centity instance, Menu window, Dialog or ProjectItem the script is
-- (a module has none). Its handlers run with `budget`.
local function new_env(scope, budget, this, package)
  budgets[scope] = budget
  namespaces[scope] = package
  local env = setmetatable({}, { __index = sandbox.scope_base(), __metatable = false })
  env._G = env
  env.nf = make_nf(scope)
  env.require = make_require(scope)
  env.log = make_log(scope)
  env.print = env.log
  env.vec3 = values.make_vec3_library(api.vec3)
  env.this = this
  envs[scope] = env
end

-- Forgets a scope's environment and everything it required.
local function drop_env(scope)
  envs[scope] = nil
  loaded[scope], loading[scope] = nil, nil
  budgets[scope], namespaces[scope] = nil, nil
end

return {
  envs = envs,
  loaded = loaded,
  load_path = load_path,
  new_env = new_env,
  drop_env = drop_env,
}
