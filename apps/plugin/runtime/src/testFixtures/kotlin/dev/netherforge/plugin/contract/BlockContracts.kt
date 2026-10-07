package dev.netherforge.plugin.contract

import dev.netherforge.format.Vec3
import dev.netherforge.plugin.platform.BlockOps
import dev.netherforge.plugin.platform.ParticleOps
import dev.netherforge.plugin.platform.ParticleSpawn
import dev.netherforge.plugin.platform.SoundOps
import dev.netherforge.plugin.platform.SoundPlay
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BlockOps]: blocks by world and position, and the data scripts keep on them. */
abstract class BlockOpsContract : PlatformContract() {
    private val blocks: BlockOps get() = platform.blocks

    @Test
    fun `a block reads back as set`() {
        main {
            val spot = at(2, 3, -6)
            val (x, y, z) = block(spot)
            val air = assertNotNull(blocks.get(world, x, y, z))
            assertEquals("minecraft:air", air.state)
            assertTrue(air.air)
            assertFalse(air.solid)
            assertFalse(air.liquid)
            place(spot, "minecraft:stone")
            val stone = assertNotNull(blocks.get(world, x, y, z))
            assertEquals("minecraft:stone", stone.state)
            assertFalse(stone.air)
            assertTrue(stone.solid)
            place(spot, "minecraft:oak_slab[type=top,waterlogged=false]")
            assertEquals("minecraft:oak_slab[type=top,waterlogged=false]", blocks.get(world, x, y, z)?.state)
        }
    }

    @Test
    fun `setting a block with neighbours told also reads back`() {
        main {
            val spot = at(3, 3, -6)
            val (x, y, z) = block(spot)
            place(spot, "minecraft:air")
            assertTrue(blocks.set(world, x, y, z, "minecraft:gold_block", true))
            assertEquals("minecraft:gold_block", blocks.get(world, x, y, z)?.state)
        }
    }

    @Test
    fun `breaking a block leaves air, and air can't be broken`() {
        main {
            val spot = at(4, 3, -6)
            val (x, y, z) = block(spot)
            place(spot, "minecraft:stone")
            assertTrue(blocks.breakNaturally(world, x, y, z, null))
            assertEquals("minecraft:air", blocks.get(world, x, y, z)?.state)
            assertFalse(blocks.breakNaturally(world, x, y, z, null))
            place(spot, "minecraft:oak_planks")
            assertTrue(blocks.breakNaturally(world, x, y, z, item("minecraft:iron_sword")))
            assertEquals("minecraft:air", blocks.get(world, x, y, z)?.state)
        }
    }

    @Test
    fun `a position's data reads back as set, and clears`() {
        main {
            val (x, y, z) = block(at(5, 3, -6))
            afterwards { blocks.setData(world, x, y, z, null) }
            assertNull(blocks.data(world, x, y, z))
            assertTrue(blocks.setData(world, x, y, z, """{"a":1}"""))
            assertEquals("""{"a":1}""", blocks.data(world, x, y, z))
            assertNull(blocks.data(world, x + 1, y, z), "only that position")
            assertTrue(blocks.setData(world, x, y, z, null))
            assertNull(blocks.data(world, x, y, z))
        }
    }

    @Test
    fun `a custom block's record reads back with its chunk's, and clears`() {
        main {
            val (x, y, z) = block(at(6, 3, -6))
            afterwards { blocks.setRecord(world, x, y, z, null) }
            assertEquals(null, blocks.records(world, x shr 4, z shr 4)!!.firstOrNull { it.x == x && it.y == y && it.z == z })
            assertTrue(blocks.setRecord(world, x, y, z, """{"id":"ore"}"""))
            val record = blocks.records(world, x shr 4, z shr 4)!!.single { it.x == x && it.y == y && it.z == z }
            assertEquals("""{"id":"ore"}""", record.json)
            assertTrue(blocks.setRecord(world, x, y, z, null))
            assertTrue(blocks.records(world, x shr 4, z shr 4)!!.none { it.x == x && it.y == y && it.z == z })
        }
    }

    @Test
    fun `the blocks of some states in a chunk are found, with which state each is`() {
        main {
            val zombie = "minecraft:note_block[instrument=zombie,note=3,powered=false]"
            val skeleton = "minecraft:note_block[instrument=skeleton,note=7,powered=true]"
            val one = block(at(7, 3, -6))
            val two = block(at(8, 3, -6))
            place(at(7, 3, -6), zombie)
            place(at(8, 3, -6), skeleton)
            val found = blocks.find(world, one.first shr 4, one.third shr 4, setOf(zombie, skeleton))!!
            assertEquals(zombie, found[dev.netherforge.plugin.platform.BlockVector(one.first, one.second, one.third)])
            assertEquals(skeleton, found[dev.netherforge.plugin.platform.BlockVector(two.first, two.second, two.third)])
            val other = blocks.find(
                world,
                one.first shr 4,
                one.third shr 4,
                setOf("minecraft:note_block[instrument=creeper,note=1,powered=false]")
            )!!
            assertEquals(emptyMap(), other)
            val only = blocks.find(world, one.first shr 4, one.third shr 4, setOf(skeleton))!!
            assertEquals(setOf(dev.netherforge.plugin.platform.BlockVector(two.first, two.second, two.third)), only.keys)
        }
    }

    @Test
    fun `how hard a state is and how a tool mines it`() {
        main {
            val stone = assertNotNull(blocks.hardness("minecraft:stone"))
            val note = assertNotNull(blocks.hardness("minecraft:note_block[instrument=zombie,note=3,powered=false]"))
            assertTrue(stone > note && note > 0, "stone $stone, a note block $note")
            assertNull(blocks.hardness("minecraft:no_such_block"))
            val pickaxe = item("minecraft:iron_pickaxe")
            assertNear(1.0, blocks.breakSpeed(null, "minecraft:stone"))
            assertTrue(blocks.breakSpeed(pickaxe, "minecraft:stone")!! > 1.0)
            assertNear(1.0, blocks.breakSpeed(pickaxe, "minecraft:oak_planks"))
            assertNull(blocks.breakSpeed(null, "minecraft:no_such_block"))
        }
    }

    @Test
    fun `a block can't be put where a player stands, and is placed for a player as they would place it`() {
        val player = join()
        main {
            val feet = block(origin)
            assertFalse(blocks.canPlace(world, feet.first, feet.second, feet.third, "minecraft:stone"))
            val free = block(at(4, 0, 4))
            assertTrue(blocks.canPlace(world, free.first, free.second, free.third, "minecraft:stone"))
            assertFalse(blocks.canPlace("nf_no_such_world", 0, 70, 0, "minecraft:stone"))
            place(at(4, -1, 4), "minecraft:stone")
            afterwards { blocks.set(world, free.first, free.second, free.third, "minecraft:air", false) }
            events.clear()
            val against = dev.netherforge.plugin.platform.BlockVector(free.first, free.second - 1, free.third)
            assertTrue(blocks.place(player.uuid, world, free.first, free.second, free.third, "minecraft:oak_planks", against, "main_hand"))
            assertEquals("minecraft:oak_planks", blocks.get(world, free.first, free.second, free.third)?.state)
            // The server's own event for it: a player placed a block.
            val heard = events.heard<dev.netherforge.plugin.platform.GameEvent.BlockPlace>("blockPlace").single { it.player == player }
            assertEquals("minecraft:oak_planks", heard.state)
            assertFalse(
                blocks.place(
                    java.util.UUID.randomUUID(),
                    world,
                    free.first,
                    free.second,
                    free.third + 1,
                    "minecraft:stone",
                    against,
                    "main_hand"
                )
            )
        }
    }

    @Test
    fun `the server is asked to leave note blocks alone, and agrees, again and again`() {
        main {
            assertTrue(blocks.freezeNoteBlocks())
            assertTrue(blocks.freezeNoteBlocks())
        }
    }

    @Test
    fun `nothing in an unloaded chunk or a missing world`() {
        val (cx, cz) = server.unloadedChunk()
        main {
            val x = cx * 16 + 1
            val z = cz * 16 + 1
            assertNull(blocks.get(world, x, 70, z))
            assertFalse(blocks.set(world, x, 70, z, "minecraft:stone", false))
            assertFalse(blocks.breakNaturally(world, x, 70, z, null))
            assertFalse(blocks.setData(world, x, 70, z, "{}"))
            assertFalse(blocks.setRecord(world, x, 70, z, "{}"))
            assertNull(blocks.records(world, cx, cz))
            assertNull(blocks.find(world, cx, cz, setOf("minecraft:stone")))
            assertFalse(blocks.canPlace(world, x, 70, z, "minecraft:stone"))
            assertNull(blocks.get(MISSING_WORLD, 0, 70, 0))
            assertFalse(blocks.set(MISSING_WORLD, 0, 70, 0, "minecraft:stone", false))
        }
    }
}

/** [ParticleOps]: batches of spawns to listed viewers. Nothing comes back to read, so this only says what's accepted. */
abstract class ParticleOpsContract : PlatformContract() {
    private val particles: ParticleOps get() = platform.particles

    @Test
    fun `spawns go to listed viewers, none, or a world that's gone, without failing`() {
        val viewer = join()
        main {
            val spawns = listOf(
                ParticleSpawn("minecraft:flame", Vec3(origin.x, origin.y + 1, origin.z), 4, Vec3(0.2, 0.2, 0.2), 0.01, null, false),
                ParticleSpawn("minecraft:end_rod", Vec3(origin.x, origin.y + 2, origin.z), 0, Vec3(0.0, 1.0, 0.0), 0.1, null, true)
            )
            particles.spawn(world, spawns, listOf(viewer))
            particles.spawn(world, spawns, emptyList())
            particles.spawn(MISSING_WORLD, spawns, listOf(viewer))
            particles.forget()
        }
    }
}

/** [SoundOps]: sounds in the world and to one player. */
abstract class SoundOpsContract : PlatformContract() {
    private val sounds: SoundOps get() = platform.sounds
    private val click = SoundPlay("minecraft:ui.button.click", "master", 1.0, 1.0)

    @Test
    fun `sounds play in worlds that exist and to players online`() {
        val player = join()
        main {
            assertTrue(sounds.play(world, Vec3(origin.x, origin.y, origin.z), click))
            assertFalse(sounds.play(MISSING_WORLD, Vec3.ZERO, click))
            assertTrue(sounds.playTo(player.uuid, click))
            assertTrue(sounds.stop(player.uuid, "minecraft:ui.button.click"))
            assertTrue(sounds.stop(player.uuid, null))
            val offline = UUID.randomUUID()
            assertFalse(sounds.playTo(offline, click))
            assertFalse(sounds.stop(offline, null))
        }
    }
}
