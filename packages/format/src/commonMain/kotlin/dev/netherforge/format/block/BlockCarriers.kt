package dev.netherforge.format.block

import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameData

/**
 * How custom blocks are held in the world: as **note block states**, which
 * the resource pack draws as the block's model. The server keeps a note
 * block, the pack redraws that exact state ([PackLayout][dev.netherforge.format.resourcepack.PackLayout]
 * writes `blockstates/note_block.json`), so a block is a cube with its own
 * look, sounds and hardness and no entity at all.
 *
 * A note block has three properties, `instrument`, `note` and `powered`
 * (23 × 25 × 2 states), and the game sets two of them by itself: the
 * instrument from what's above and below, and `powered` from redstone. The
 * server (the plugin's adapter) stops it doing either (Paper's
 * `disable-noteblock-updates`), so a state stays what it was set to, and
 * instead plays the vanilla note blocks' part itself: **vanilla note blocks
 * live in the default instrument's column** (every note, powered or not: what
 * a player places, tunes and plays), which the server tunes and plays as the
 * game does, working the instrument out from the blocks around it when it
 * sounds. Every other state is a **carrier**: no player's note block is ever
 * in one, and the plugin never plays or tunes one.
 *
 * The pool of carriers is ordered rarest first (the last instruments the game
 * lists, which only a mob head above a note block gives, then `powered`, then
 * the note), so a world that already has note blocks in odd states is the
 * least likely to meet a custom block's state. Blocks take the pool's states
 * in the order of their names ([plan]). A block's state can move when the
 * project's blocks change, so the server remembers which block each
 * position is (not the state) and sets the state again when its chunk loads.
 */
object BlockCarriers {
    /** The block whose states carry custom blocks. */
    const val BLOCK = "minecraft:note_block"

    /** What the pack draws every state of [BLOCK] that no custom block uses as: the game's own look. */
    const val VANILLA_MODEL = "minecraft:block/note_block"

    const val INSTRUMENT = "instrument"
    const val NOTE = "note"
    const val POWERED = "powered"

    /** The states of [BLOCK] in the game being asked, split into vanilla's column and the pool of carriers. */
    class Pool internal constructor(val info: BlockInfo) {
        private val instruments = info.properties.getValue(INSTRUMENT)
        private val notes = info.properties.getValue(NOTE)
        private val powered = info.properties.getValue(POWERED)

        /** The instrument vanilla note blocks keep: the default one. */
        val vanillaInstrument: String = info.defaults[INSTRUMENT] ?: instruments.first()

        private fun state(instrument: String, note: String, powered: String) =
            BlockState(BLOCK, mapOf(INSTRUMENT to instrument, NOTE to note, POWERED to powered))

        /** Every state the block has. */
        val all: List<BlockState> = instruments.flatMap { i -> notes.flatMap { n -> powered.map { p -> state(i, n, p) } } }

        /** The states custom blocks may take, rarest first. */
        val carriers: List<BlockState> = instruments.reversed()
            .filter { it != vanillaInstrument }
            .flatMap { i -> powered.sortedBy { it != "true" }.flatMap { p -> notes.map { n -> state(i, n, p) } } }

        /** Whether [state] is a note a player's note block can be in (the game's own look, tuned and played by the server). */
        fun isVanilla(state: BlockState): Boolean = state.id == BLOCK && state.properties[INSTRUMENT] == vanillaInstrument

        /** Whether [state] is one of the pool's: where a custom block could be. */
        fun isCarrier(state: BlockState): Boolean = state.id == BLOCK && !isVanilla(state)
    }

    /** The note block's states in [game], or null when it has none (no game data, or a game without them). */
    fun pool(game: GameData?): Pool? {
        val info = game?.block(BLOCK) ?: return null
        if (listOf(INSTRUMENT, NOTE, POWERED).any { info.properties[it].isNullOrEmpty() }) return null
        return Pool(info)
    }

    /**
     * One block's use of a carrier: [name] is the block as the project names
     * it, [pack] the pack its model is in (`ui`, or `library:ui` for a
     * package's) and [entry] its key there, both null for a block with no
     * model yet. [hidden] is a block drawn by a centity, which shows no cube
     * of its own.
     */
    class Use(val name: String, val state: BlockState, val pack: String?, val entry: String?, val hidden: Boolean)

    /**
     * Which carrier each block takes. [overflow] is the blocks there was no
     * state left for, which take none. Blocks whose model isn't shaped like a
     * reference are left out (validation says so).
     */
    class Plan(val pool: Pool, val home: String, val uses: List<Use>, val overflow: List<String>) {
        private val byName = uses.associateBy { it.name }
        private val byState = uses.associateBy { it.state.toString() }

        /** The carrier [name] is held as, or null for a block that has none. */
        fun stateOf(name: String): BlockState? = byName[name]?.state

        /** The block that [state] (canonical text) is the carrier of. */
        fun nameOf(state: String): String? = byState[state]?.name
    }

    /**
     * Gives every block in [blocks] (by the name the project knows it by:
     * `ruby_ore`, or `library:gem` for a package's) a state of the pool, in
     * name order. A block that doesn't run (one with an error in its file) is
     * a null: it keeps its place in the order so the others' states don't
     * move while it's being fixed, and is drawn as the game draws a note
     * block. A running block's references are written in full (a package's
     * block names `library:gems/gem`), so its model resolves in [home], the
     * project's namespace. Null when [game] has no note block states.
     */
    fun plan(blocks: Map<String, BlockFile?>, home: String, game: GameData?): Plan? {
        val pool = pool(game) ?: return null
        val uses = mutableListOf<Use>()
        val overflow = mutableListOf<String>()
        var next = 0
        for (name in blocks.keys.sorted()) {
            val block = blocks.getValue(name)
            val model = block?.model?.resolve(home)
            if (block?.model != null && model == null) continue
            if (next >= pool.carriers.size) {
                overflow += name
                continue
            }
            val pack = model?.let { if (it.namespace == home) it.pack else "${it.namespace}:${it.pack}" }
            uses += Use(name, pool.carriers[next++], pack, model?.key, block?.centity != null)
        }
        return Plan(pool, home, uses, overflow)
    }

    /** How many blocks the game's note block has states for, or 0 without game data. */
    fun capacity(game: GameData?): Int = pool(game)?.carriers?.size ?: 0
}
