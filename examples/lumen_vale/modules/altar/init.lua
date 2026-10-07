-- The lumen altar's rules: what an item set in the altar's ring (menus/lumen_altar) becomes,
-- and what infusing it costs. The menu's script only shows and clicks; everything it decides
-- is here, so tests (and /vale) can infuse without a window.

local altar = {}

local SHARD = { item = "lumen_shard" }
local ESSENCE = { item = "wisp_essence" }

---@class AltarRule
---@field name string
---@field label string What the infuse button says it will do.
---@field shards integer Lumen shards it takes from the player.
---@field essence integer Wisp essence it takes from the player.
---@field matches fun(item: Item): boolean Whether it takes this item in the ring.
---@field result fun(item: Item): Item What the ring holds afterwards.

---@type AltarRule[]
altar.RULES = {
  {
    name = "kindle",
    label = "Kindle the lantern",
    shards = 3,
    essence = 0,
    matches = function(item)
      return nf.items.id(item) == "lumen_lantern" and not (item.data and item.data.kindled)
    end,
    result = function()
      return nf.items.create("lumen_lantern", {
        name = "<light_purple>Kindled Lantern",
        lore = {
          "<gray>It burns without fuel, and pulls upward.",
          "<dark_gray>Raise it at a shrine",
        },
        glint = true,
        data = { kindled = true },
      })
    end,
  },
  {
    name = "circlet",
    label = "Set lumen into the circlet",
    shards = 4,
    essence = 1,
    matches = function(item)
      return item.kind == "minecraft:golden_helmet" and nf.items.id(item) == nil
    end,
    result = function()
      return nf.items.create("lumen_circlet")
    end,
  },
  {
    name = "bottle",
    label = "Draw a wisp's light into the bottle",
    shards = 2,
    essence = 0,
    matches = function(item)
      return item.kind == "minecraft:glass_bottle"
    end,
    result = function()
      return nf.items.create("wisp_essence")
    end,
  },
}

--- The rule that takes an item, or `nil` when the altar does nothing with it.
---@param item Item?
---@return AltarRule?
function altar.rule_for(item)
  if not item then
    return nil
  end
  for _, rule in ipairs(altar.RULES) do
    if rule.matches(item) then
      return rule
    end
  end
  return nil
end

local function cost_text(rule)
  local text = ("<glyph:lumen/lumen> <white>%d</white> lumen shards"):format(rule.shards)
  if rule.essence > 0 then
    text = text .. (", <glyph:lumen/wisp> <white>%d</white> wisp essence"):format(rule.essence)
  end
  return text
end

--- The infuse button's lore for what's in the ring.
---@param item Item?
---@return string[]
function altar.describe(item)
  if not item then
    return { "<gray>Set something in the ring:", "<gray>a lantern, a golden helmet, a bottle" }
  end
  local rule = altar.rule_for(item)
  if not rule then
    return { "<red>The lumen doesn't answer to that" }
  end
  return { "<light_purple>" .. rule.label, "<gray>Costs " .. cost_text(rule) }
end

--- Infuses one of the item in the ring, taking the cost from the player's inventory. Gives
--- `result, rest` (the infused item, and what's left of a stack that was in the ring, or
--- `nil`), or `nil, why` when it can't.
---@param player Player
---@param item Item
---@return Item?
---@return Item|string|nil
function altar.infuse(player, item)
  local rule = altar.rule_for(item)
  if not rule then
    return nil, "The lumen doesn't answer to that"
  end
  local inventory = assert(player:inventory(), "an offline player has no inventory")
  local enough = inventory:has_item(SHARD, rule.shards)
    and (rule.essence == 0 or inventory:has_item(ESSENCE, rule.essence))
  if not enough then
    return nil, "The altar asks for " .. cost_text(rule)
  end
  inventory:remove_item(SHARD, rule.shards)
  if rule.essence > 0 then
    inventory:remove_item(ESSENCE, rule.essence)
  end
  local rest = nil
  if (item.count or 1) > 1 then
    rest = item
    rest.count = item.count - 1
  end
  nf.emit("lumen_vale:infused", { player = player, rule = rule.name })
  return rule.result(item), rest
end

return altar
