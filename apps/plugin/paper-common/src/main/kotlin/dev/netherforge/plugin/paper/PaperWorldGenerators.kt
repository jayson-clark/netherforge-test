package dev.netherforge.plugin.paper

import dev.netherforge.format.terrain.TerrainGenerator
import dev.netherforge.plugin.platform.ProjectGenerator
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.block.data.BlockData
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * The project's terrains on Paper (`terrain/<id>.json`, format's
 * [dev.netherforge.format.terrain.CompiledTerrain]): a Bukkit [ChunkGenerator]
 * and [BiomeProvider] per world that ask the published generator by id each
 * time, so hot reload swaps it for the chunks generated afterwards.
 *
 * **Threads.** The server calls a generator from its chunk threads, so
 * [isParallelCapable][ChunkGenerator.isParallelCapable] is true and nothing
 * here touches the main thread's world: a published generator is immutable
 * (format's compiled file, its bound generators' noise only read, the
 * [BlockData] prepared on the main thread when it was published), and the
 * published set is one atomic reference.
 *
 * What a chunk gets is [TerrainGenerator.generate]'s blocks written as chunk
 * data (runs of one block, air left out); a custom block's note block state is
 * written like any, and the runtime's blocks adopt it when the chunk loads.
 * The biomes are the areas' (the project's own from the server's registry,
 * which has them from the start-up datapack), and the game then decorates the
 * chunk with their features. A file's Lua stages run inside [TerrainGenerator.generate],
 * in a Lua state of the generator's for each chunk thread at a time (format's
 * `TerrainScripts`), never the plugin's own.
 */
class PaperWorldGenerators(private val logger: Logger) {
    /** A published generator, ready for the chunk threads: its blocks as [BlockData], its biomes, and the world-bound generators made from it. */
    private class Prepared(val project: ProjectGenerator, val blocks: Array<BlockData?>, val biomes: Map<String, Biome>) {
        private val bound = ConcurrentHashMap<WorldShape, TerrainGenerator>()

        fun boundTo(seed: Long, minY: Int, maxY: Int): TerrainGenerator =
            bound.computeIfAbsent(WorldShape(seed, minY, maxY)) { project.terrain.bind(seed, minY, maxY, project.scripts) }

        /** Lets go of the Lua states its script ran in (chunks being made with it finish, and theirs go then). */
        fun close() = bound.values.forEach { it.close() }

        fun biome(id: String): Biome = biomes[id] ?: Biome.PLAINS
    }

    private data class WorldShape(val seed: Long, val minY: Int, val maxY: Int)

    private val published = AtomicReference<Map<String, Prepared>>(emptyMap())

    /** Publishes [generators] (on the main thread: block data is made here, once, not on the chunk threads). */
    fun publish(generators: Map<String, ProjectGenerator>) {
        val before = published.getAndSet(generators.mapValues { (id, project) -> prepare(id, project) })
        // A replaced generator's script states go; one no longer published stays, since its worlds keep generating with it.
        for ((id, prepared) in before) if (id in generators) prepared.close()
    }

    private fun prepare(id: String, project: ProjectGenerator): Prepared {
        val stone = project.terrain.palette[project.terrain.stone].label
        val blocks = project.states.mapIndexed { index, state ->
            if (index == 0) {
                null
            } else {
                try {
                    Bukkit.createBlockData(state)
                } catch (e: IllegalArgumentException) {
                    logger.warning("Terrain $id: \"$state\" isn't a block this server knows (${e.message}); it's left as $stone")
                    Bukkit.createBlockData(stone)
                }
            }
        }.toTypedArray()
        // A project biome is in the registry from the start-up datapack: one added since the server started isn't yet.
        val biomes = project.biomes.mapValues { (_, key) ->
            NamespacedKey.fromString(key)?.let(Registry.BIOME::get) ?: Biome.PLAINS.also {
                logger.warning("Terrain $id: this server has no biome \"$key\" (until it restarts, for a new one); it's plains")
            }
        }
        return Prepared(project, blocks, biomes)
    }

    /** What one world holds of its generator: the last one published under its id, so a generator that's gone keeps generating. */
    private inner class Holder(val id: String) {
        @Volatile private var last: Prepared? = null

        /** The generator now, or the last one this world had; null when it never had one. */
        fun current(): Prepared? {
            val now = published.get()[id]
            if (now != null) {
                last = now
                return now
            }
            return last
        }
    }

    /** The chunk generator and biome provider of a world made with the project generator [id]: one pair per world, sharing what they hold. */
    fun forWorld(id: String): Pair<ChunkGenerator, BiomeProvider> {
        val holder = Holder(id)
        val biomes = ProjectBiomes(holder)
        return ProjectChunks(holder, biomes) to biomes
    }

    private inner class ProjectChunks(private val holder: Holder, private val biomes: ProjectBiomes) : ChunkGenerator() {
        override fun generateNoise(info: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, data: ChunkGenerator.ChunkData) {
            val prepared = holder.current() ?: return
            val buffer = prepared.boundTo(info.seed, data.minHeight, data.maxHeight).generate(chunkX, chunkZ)
            val height = buffer.height
            for (lx in 0 until 16) {
                for (lz in 0 until 16) {
                    val start = buffer.columnStart(lx, lz)
                    var y = 0
                    while (y < height) {
                        val block = buffer.blocks[start + y]
                        var end = y
                        while (end + 1 < height && buffer.blocks[start + end + 1] == block) end++
                        // Air is what a new chunk holds already.
                        prepared.blocks[block]?.let { data.setRegion(lx, buffer.minY + y, lz, lx + 1, buffer.minY + end + 1, lz + 1, it) }
                        y = end + 1
                    }
                }
            }
        }

        override fun getDefaultBiomeProvider(worldInfo: WorldInfo): BiomeProvider = biomes

        override fun getBaseHeight(info: WorldInfo, random: Random, x: Int, z: Int, heightMap: HeightMap): Int {
            val prepared = holder.current() ?: return info.minHeight
            val generator = prepared.boundTo(info.seed, info.minHeight, info.maxHeight)
            val top = generator.surfaceAt(x, z)
            // The first free block above what the map counts: the ground, or the sea over it for the maps that see water.
            val sea = prepared.project.terrain.seaLevel
            return when (heightMap) {
                HeightMap.OCEAN_FLOOR, HeightMap.OCEAN_FLOOR_WG -> top + 1
                else -> maxOf(top, sea) + 1
            }
        }

        override fun getFixedSpawnLocation(world: World, random: Random): Location? {
            val prepared = holder.current() ?: return null
            val generator = prepared.boundTo(world.seed, world.minHeight, world.maxHeight)
            val (x, z) = generator.dryColumnNear(0, 0) ?: return null
            return Location(world, x + 0.5, generator.surfaceAt(x, z) + 1.0, z + 0.5)
        }

        // Each of these asks for the game's own stage to run *after* ours, filling in what ours left (the game's noise
        // would put its stone wherever there's air): the terrain is all of it ours, so none of them runs.
        override fun shouldGenerateNoise() = false

        override fun shouldGenerateSurface() = false

        override fun shouldGenerateBedrock() = false

        override fun shouldGenerateCaves() = false

        // The game's decoration stage places each biome's features (a game biome's own trees, flowers, ores and lakes; a
        // project biome exactly the ones its file lists) and the pieces of the structures that started: it always runs,
        // so what decorates the world is the biomes', and `structures.vanilla` is only about structures.
        override fun shouldGenerateDecorations() = true

        override fun shouldGenerateMobs() = false

        override fun shouldGenerateStructures() = vanillaStructures()

        private fun vanillaStructures() = holder.current()?.project?.terrain?.vanillaStructures == true

        override fun isParallelCapable() = true
    }

    /**
     * A world's biomes. The server asks [getBiomes] once, as it makes the world, and works out from that list everything
     * it decorates with (the features of every step, in one order) and what `/locate biome` searches: a biome outside it
     * would have features the server can't place. So a generator published since, whose areas name a biome the world
     * didn't start with, has that biome's areas as the world's first one until the world is loaded again (a restart).
     */
    private inner class ProjectBiomes(private val holder: Holder) : BiomeProvider() {
        @Volatile private var possible: Set<Biome>? = null
        private val outside = ConcurrentHashMap.newKeySet<Biome>()

        override fun getBiome(info: WorldInfo, x: Int, y: Int, z: Int): Biome {
            val prepared = holder.current() ?: return Biome.PLAINS
            val biome = prepared.biome(prepared.boundTo(info.seed, info.minHeight, info.maxHeight).biomeAt(x, z))
            val started = possible ?: return biome
            if (biome in started) return biome
            if (outside.add(biome)) {
                logger.warning(
                    "World ${info.name}: its generator ${holder.id} now has the biome ${biome.key().asString()} in an area, which the " +
                        "world didn't start with; it's ${started.first().key().asString()} there until the world is loaded again (restart the server)"
                )
            }
            return started.first()
        }

        override fun getBiomes(info: WorldInfo): List<Biome> {
            val prepared = holder.current() ?: return listOf(Biome.PLAINS)
            val started = possible ?: LinkedHashSet(prepared.project.terrain.biomes.map { prepared.biome(it) }).also { possible = it }
            return started.toList()
        }
    }
}
