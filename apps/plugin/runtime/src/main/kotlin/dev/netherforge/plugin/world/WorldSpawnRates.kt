package dev.netherforge.plugin.world

import dev.netherforge.format.project.WorldConfig
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.session.RuntimeService

/**
 * The spawn rates `netherforge.json`'s `worlds` sets (`spawnLimits`,
 * `spawnIntervals`): put in place for every world it names that's loaded when
 * the session starts (so a reload re-applies them, over what scripts set with
 * `world:set_spawn_limit`), and for each one loaded or created later.
 * Categories it doesn't name keep whatever the world has.
 */
internal class WorldSpawnRates(private val platform: Platform, private val worlds: () -> Map<String, WorldConfig>?) : RuntimeService {
    override val name get() = "spawn rates"

    override fun start() {
        for (world in worlds().orEmpty().keys) {
            if (platform.worlds.exists(world)) apply(world)
        }
    }

    override fun worldLoaded(world: String) = apply(world)

    private fun apply(world: String) {
        val config = worlds()?.get(world) ?: return
        for ((category, limit) in config.spawnLimits.orEmpty()) platform.worlds.setSpawnLimit(world, category, limit)
        for ((category, ticks) in config.spawnIntervals.orEmpty()) platform.worlds.setSpawnInterval(world, category, ticks)
    }
}
