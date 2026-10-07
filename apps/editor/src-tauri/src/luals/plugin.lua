-- NetherForge's lua-language-server plugin: `require` as the server resolves it.
--
-- On the server, a script of a resource (a centity, menu, dialog or item) finds
-- `require("lib.steps")` beside its script first (`centities/tower/lib/steps.lua`),
-- and only then in `modules/`. LuaLS has one search path for the whole project, so
-- a pattern like `?.lua` would match a `lib/steps.lua` in any folder. This hook
-- answers for a file inside a resource; everything else (and a name not found
-- beside the script) falls through to `runtime.path`, which reaches `modules/`.
--
-- The editor's backend writes this file into its data folder and the editor's
-- language client points LuaLS at it (`Lua.runtime.plugin`), with the scripted
-- kinds as arguments: `"centities=centity.json"`, one per folder whose resources
-- have a main file naming their script.
--
-- A package's module (`require("library:greetings")`) resolves to the stub the
-- editor writes for it (`packages=<folder>`, see `core/luals/stubs.ts`): one per
-- file of each module a package the project depends on exports.

local files = require('files')
local furi = require('file-uri')
local json = require('json')
local util = require('utility')

local _, _, args = ...

--- `centities` → `centity.json`, from the arguments.
local mainFiles = {}
--- Where the editor writes its stubs of the packages' exported modules
--- (`packages=.netherforge/luals/packages`), relative to the project.
local packageStubs
for _, arg in ipairs(type(args) == 'table' and args or {}) do
    local folder, main = tostring(arg):match('^([%w_]+)=([%w_.]+)$')
    local stubs = tostring(arg):match('^packages=([%w_./]+)$')
    if stubs then
        packageStubs = stubs
    elseif folder then
        mainFiles[folder] = main
    end
end

--- `require("library:greetings")` (a module the package `library` exports, or
--- `library:greetings.messages` for a file in it): the editor's stub of that
--- file. Nil when the package doesn't export it (requiring it is an error).
local function packageModule(uri, name)
    local namespace, module = name:match('^([%w_]+):([%w_][%w_.]*)$')
    if not namespace or not packageStubs or module:find('..', 1, true) or module:sub(-1) == '.' then
        return nil
    end
    local base = uri .. '/' .. packageStubs .. '/' .. namespace .. '/' .. module:gsub('%.', '/')
    for _, candidate in ipairs({ base .. '.lua', base .. '/init.lua' }) do
        if files.exists(candidate) then
            return { candidate }
        end
    end
    return nil
end

--- The script a resource's main file names (`script.file`), as a path under the project.
local function scriptOf(scopeUri, folder, main)
    local text = util.loadFile(furi.decode(scopeUri .. '/' .. folder .. '/' .. main))
    if not text then
        return nil
    end
    local ok, model = pcall(json.decode, text)
    if not ok or type(model) ~= 'table' or type(model.script) ~= 'table' then
        return nil
    end
    local file = model.script.file
    if type(file) ~= 'string' or file:find('..', 1, true) then
        return nil
    end
    return folder .. '/' .. file
end

---@param uri string the workspace's
---@param name string what's required
---@param suri string the requiring file's
---@return string[]?
function ResolveRequire(uri, name, suri)
    if not suri or suri:sub(1, #uri + 1) ~= uri .. '/' then
        return nil
    end
    if name:find(':', 1, true) then
        return packageModule(uri, name)
    end
    -- Dotted parts of letters, digits and `_`, as the server accepts.
    if not name:match('^[%w_]+$') and not name:match('^[%w_][%w_.]*[%w_]$') or name:find('..', 1, true) then
        return nil
    end
    local relative = suri:sub(#uri + 2)
    local kind, id = relative:match('^([^/]+)/([^/]+)/')
    local main = kind and mainFiles[kind]
    if not main then
        return nil
    end
    local resource = kind .. '/' .. id
    local script = scriptOf(uri, resource, main)
    local folder = script and script:match('^(.*)/') or resource
    local path = name:gsub('%.', '/')
    for _, candidate in ipairs({ folder .. '/' .. path .. '.lua', folder .. '/' .. path .. '/init.lua' }) do
        -- The server refuses the script itself: it's already running.
        local found = uri .. '/' .. candidate
        if candidate ~= script and files.exists(found) then
            return { found }
        end
    end
    return nil
end
