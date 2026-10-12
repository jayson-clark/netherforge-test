# Skies

A sky is a set of colours a script can put over a world, or over one player's
view of it, for as long as something lasts: a blood moon, an eclipse, a
pumpkin moon. While it's set, everyone under it sees its colours in place of
each biome's own; when it's cleared, the biomes' own come back. Each is one
file, `skies/<id>.json`, with nothing beside it.

```json
{
  "$schema": "../.netherforge/schema/sky.schema.json",
  "sky": "#5a0a0a",
  "fog": "#3a0606",
  "water": "#8a1010",
  "waterFog": "#4a0808",
  "fade": 3
}
```

| Key        | Meaning                                                                                                 | Default     |
| ---------- | ------------------------------------------------------------------------------------------------------- | ----------- |
| `sky`      | The sky's colour, `#rrggbb`.                                                                            | the biome's |
| `fog`      | The fog's: what the land in the distance fades into.                                                    | the biome's |
| `clouds`   | The clouds'.                                                                                            | the biome's |
| `water`    | Water's own colour.                                                                                     | the biome's |
| `waterFog` | The fog under water.                                                                                    | the biome's |
| `fade`     | How many steps a fade to it takes, 1 to 8: more is smoother, and more for players to load as they join. | 3           |

At least one colour is needed (`sky.empty`); each is written `#rrggbb`
(`sky.color`), and `fade` is 1 to 8 (`sky.fade`).

## Setting one

```lua
local world = nf.worlds.default()
world:set_sky("blood_moon", { fade_ticks = 100 })   -- everyone in the world, fading in over 5 s
world:clear_sky({ fade_ticks = 100 })               -- and back
player:set_sky("eclipse")                           -- one player, whatever world they're in
player:clear_sky()                                  -- their world's again
```

`world:sky()` and `player:sky()` say which is set. A player's own sky goes
over their world's; when it's cleared they see their world's again. A fade
moves through the sky's `fade` steps, part of the way from each biome's
colours to the sky's, evenly over `fade_ticks`; from one sky to another, the
first fades out and then the second in. Nothing is kept: a restart (or a
reload of the project on the dev server) clears every sky, so set it again
from a script when the event is still on.

## How it works, and what it can't do

The game's client takes these colours from the **biomes** the world is made
of, and learns the biomes as a player joins. So as the server starts, each sky
is made into a set of _twin biomes_: one of every biome the server has (the
game's own and the project's), per step of its fade, each the biome with the
sky's colours and nothing to generate. A player under a sky is told the chunks
around them are made of those twins, and then the client draws them so. The
world itself never changes: its biomes, mobs, weather and what a script reads
of it are as they were, and a player who isn't under the sky sees nothing
different.

- **A new or changed sky needs a restart**, as a biome does: setting a sky the
  server didn't start with is an error that says so.
- **Grass, leaves, weather, sounds and music** stay each biome's own: only the
  colours a sky gives change. The colours blend from biome to biome as the
  game's always do.
- **The sun, the moon and the stars** aren't colours a biome has: a sky can't
  turn the moon red. Draw that with a resource pack's textures if you need it.
- **Night still darkens it**: the game's own day and night move a sky's colours
  as they move a biome's.
- **A passed-through datapack's biomes** (`datapacks/`) have no twins and keep
  their own colours under a sky.
- Each step of each sky is as many biomes as the server has (about 65 of the
  game's, and the project's), which players load as they join: a few skies with
  a few steps each are nothing to worry about.
