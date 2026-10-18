-- Booth: centities. A centity is an entity made of nodes (block, item, model and text displays),
-- each with a transform, a hitbox and keyframed animations, and a script of its own.
local expo = require("expo")

expo.booth({
  id = "centities",
  title = "Centities & animation",
  summary = "Entities built from nodes, animated by keyframes, run by scripts",
  icon = "minecraft:armor_stand",
  lines = {
    "<gold>Centities</gold> are the project's own entities (centities/<id>/centity.json).",
    "",
    "<white>Nodes</white> <gray>are a tree of displays: blocks, items, models from the resource pack,",
    "<gray>and text. Each has a transform and a hitbox players can click.",
    "<white>Animations</white> <gray>are keyframed tracks per node, with easing; some autoplay.",
    "<white>Scripts</white> <gray>(script.lua beside the file) run once per instance, with `this`.",
    "",
    "<gray>Try each one: they all answer a click.",
  },
  build = function(booth)
    booth:spawn("tower", 4, 1, 2)
    booth:caption(
      4,
      2,
      "<white>Stone tower\n<gray>Three nodes; click to play its `spin` animation. turns.lua beside the script counts each tower's own turns.",
      3.6
    )
    booth:spawn("lamp", 4, 1.5, 5)
    booth:caption(
      4,
      5,
      "<white>Floating lamp\n<gray>A `bob` animation that autoplays and loops, with a text node riding along.",
      3.4
    )
    booth:spawn("crystal", 4, 1, 8)
    booth:caption(
      4,
      8,
      "<white>Model flipbook\n<gray>One model display; the script swaps its model every 10 ticks while `spin` turns it.",
      3.2
    )
    booth:spawn("door", 6, 1, 10.5)
    booth:caption(
      4,
      10.5,
      "<white>Swinging door\n<gray>Click: a task plays a particle effect, waits for it, opens, waits 2s, closes. Written top to bottom.",
      3.4
    )
  end,
})
