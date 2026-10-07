package dev.netherforge.plugin.paper.version

import dev.netherforge.plugin.paper.WorldStorage
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.GoalSelector
import org.bukkit.WorldCreator

/**
 * What the shared server code here (`apps/plugin/paper-internals/src/main`)
 * reaches differently on each Minecraft version. Each adapter's `Mojang`
 * object (`apps/plugin/paper-<minecraft>/src/main`) answers it for its own
 * server; the bots have their own, `BotProtocol`.
 */
internal interface ServerInternals {
    /** A mob's goal selector (the one that isn't for targets). */
    fun goalSelector(mob: Mob): GoalSelector

    /** Where this version keeps a world's files: Paper's API is the same either way. */
    val worlds: WorldStorage

    /**
     * Saves the generation settings of the new world [creator] describes, as `createWorld` would make them for it (the
     * world preset of its type, its generator settings, seed and structures), but with dimension type [type] (a key
     * the server has: `basic:deep`) for its environment's level stem. `createWorld` then finds a saved world's
     * settings and makes the world with that type: Bukkit has no other way to give a world a datapack's dimension type
     * (see [dev.netherforge.plugin.paper.PaperVersion.prepareDimension]). Where they're saved is the version's: the
     * world's own `world_gen_settings` saved data from 26.1, its `level.dat` before.
     */
    fun prepareDimension(creator: WorldCreator, type: String)
}
