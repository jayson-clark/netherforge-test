local this = this --[[@as ProjectBlock]]

-- Lumen ore: the terrain scatters it through the stone of the vale (terrain/vale.json's
-- `lumen` ore and its cave decorations), and the plugin adopts each generated one as this
-- block when its chunk loads, so this hears ore nobody placed.

-- The loot table (loot/lumen_ore.json) has rolled the shards already. Mining by night, when
-- the wisps are out, shakes loose a little more light.
this:on("break", function(event)
  local time = event.block:world():time_of_day()
  if time and time >= 13000 and time < 23000 and math.random() < 0.25 then
    local drops = event.drops
    drops[#drops + 1] = nf.items.create("lumen_shard")
    event.drops = drops
    event.player:send_actionbar("<light_purple><glyph:lumen/lumen> The ore glows brighter by night")
  end
  nf.emit("lumen_vale:ore_mined", { player = event.player })
end)
