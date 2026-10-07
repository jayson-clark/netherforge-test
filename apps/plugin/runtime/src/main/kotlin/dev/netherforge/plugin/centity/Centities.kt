package dev.netherforge.plugin.centity

import dev.netherforge.format.Vec3
import dev.netherforge.format.bridge.InstanceInfo
import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.CompiledCentity
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.project.CentityKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.api.AnimationEvent
import dev.netherforge.plugin.api.CentityEvent
import dev.netherforge.plugin.api.CentityPathEndEvent
import dev.netherforge.plugin.api.ClickEvent
import dev.netherforge.plugin.api.EventType
import dev.netherforge.plugin.api.Events
import dev.netherforge.plugin.api.LuaEvent
import dev.netherforge.plugin.api.LuaHandle
import dev.netherforge.plugin.api.TickEvent
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.DisplayPose
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import dev.netherforge.plugin.store.Store
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Every live centity instance, and the per-tick pipeline that drives them:
 *
 *  1. **animate**: playing clips advance and pose the nodes; then a walk
 *     `move_to` started moves the whole centity a step (see
 *     [CentityPaths]); then **physics** moves the bodies (see [Physics]);
 *  2. **scripts**: `animation_end` handlers, then `tick` handlers (only for
 *     an instance something listens to `tick` on); a script's write lands on
 *     top of the animated pose;
 *  3. **sync**: compose world matrices and push only what changed to the
 *     entities.
 *
 * Only sync touches the world, so an instance nothing changed costs nothing,
 * and an instance whose chunk isn't loaded is skipped entirely until it is.
 *
 * The centity's events are raised from here (and from [Physics] for a
 * node's body): `spawn` after a new instance's script has run, `remove`
 * before it unloads, `chunk_load` and `chunk_unload` as its anchor's chunk
 * comes and goes, and clicks along their path from node to `nf`.
 *
 * Instances whose definition is gone (deleted, or never loaded) are kept as
 * **inert** records: their entities stay where they are and they come back to
 * life when the definition does, rather than being destroyed by a typo.
 *
 * The instances outlive the session: it starts from the last one's [records]
 * (or the store's, on enable). The store hears of an instance's identity
 * (spawned, removed, its entities, its centity) at the end of the tick that
 * changed it. Its place and facing change all the time (a turret aiming, a
 * body falling, a walk) and are kept in memory; the store gets them when
 * they'd be saved anyway: when the world saves, when the anchor's chunk
 * unloads and when the session stops. So the store's anchors are as old as
 * the world's own entities after a crash, and a centity turning every tick
 * writes nothing.
 */
class Centities(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    private val readSource: (String) -> String?,
    /** Where instances are kept. */
    private val store: Store.Instances,
    /** The instances the session starts with, reattached as it defines the centities. */
    private val restored: List<InstanceRecord>,
    /** The project's namespace: records name centities in full, the registry as the project does. */
    private val home: () -> String,
    /** An instance was removed for good (or its inert record was): what's kept for it elsewhere goes too. */
    private val onRemoved: (UUID) -> Unit = {}
) : RuntimeService {
    override val name get() = "centities"

    /** Instances whose identity changed since the store last heard: written at the end of the tick. */
    private val dirty = LinkedHashSet<UUID>()

    /** Each instance's place and facing as the store has them. */
    private val stored = HashMap<UUID, Placement>()

    private data class Placement(val anchor: Location, val yaw: Double)

    private val definitions = LinkedHashMap<String, CompiledCentity>()
    private val instances = LinkedHashMap<UUID, Instance>()
    private val inert = LinkedHashMap<UUID, InstanceRecord>()

    /** Entity → the instance and node owning it. */
    private val owners = HashMap<UUID, Pair<Instance, String>>()

    val physics = Physics(platform, this)

    /** Centities walking where `move_to` sent them. */
    val paths = CentityPaths(platform, this)

    fun definition(id: String): CompiledCentity? = definitions[id]

    fun definitionIds(): List<String> = definitions.keys.sorted()

    fun all(): List<Instance> = instances.values.toList()

    /** Every instance's hitboxes by where they are, for physics. */
    internal val obstacles = ObstacleGrid(::boxesOf)

    fun find(id: UUID): Instance? = instances[id]

    fun find(id: String): Instance? = runCatching { UUID.fromString(id) }.getOrNull()?.let { instances[it] }

    fun count(): Int = instances.size + inert.size

    /** Takes every definition and rebuilds the instances from [restored] (inert where their centity is gone), without starting their scripts. */
    override fun define(project: SessionProject) {
        definitions.clear()
        definitions.putAll(project.running(CentityKind))
        for (record in restored) restoreOne(record)
    }

    override fun start() = startAll()

    /** Scripts stop; the store gets what changed, places and facings included. */
    override fun stop() {
        stopAll()
        flush()
        savePlacements { true }
    }

    override fun tick(phase: TickPhase) {
        when (phase) {
            TickPhase.WORLD -> pass()
            TickPhase.SAVE -> flush()
            else -> {}
        }
    }

    /** The world is saving its entities: the store gets where its instances stand now, as of the same moment. */
    override fun worldSaving(world: String) = savePlacements { it.anchor.world == world }

    /** The chunk goes, and the entities standing at its instances' anchors with it: their places are saved now. */
    override fun chunkUnloading(world: String, chunkX: Int, chunkZ: Int) = savePlacements {
        it.anchor.world == world && chunk(it.anchor.x) == chunkX && chunk(it.anchor.z) == chunkZ
    }

    /** Every place and facing that changed, for `/nf data` and tests: as the world saving would. */
    fun savePlacements() = savePlacements { true }

    private fun savePlacements(which: (Instance) -> Boolean) {
        for (instance in instances.values) {
            if (!instance.natural && which(instance) && stored[instance.id] != placement(instance)) put(record(instance))
        }
    }

    private fun chunk(coordinate: Double) = Math.floorDiv(kotlin.math.floor(coordinate).toLong(), 16L).toInt()

    private fun placement(instance: Instance) = Placement(instance.anchor, instance.yaw)

    override fun liveness() = listOf(
        liveness<LuaHandle.Centity> { find(it.id)?.takeIf { instance -> !instance.removed } != null },
        liveness<LuaHandle.Node> { node -> find(node.centity)?.takeIf { !it.removed }?.indexOf(node.name) != null }
    )

    override val reloads: Set<KindSpec<*, *>> get() = setOf(CentityKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            batch.resource(Resource(CentityKind, id), CentityKind.pathOf(id), batch.snapshot.running(CentityKind)[id]) { reload(id, it) }
        }
    }

    /** [id]'s identity changed (it was spawned or removed, went inert or came back, its entities changed): the store hears at the end of the tick. */
    private fun changed(id: UUID) {
        dirty += id
    }

    /** Stages what changed for the store: each instance (or inert record) as it is now, or its removal. */
    private fun flush() {
        if (dirty.isEmpty()) return
        for (id in dirty) {
            val instance = instances[id]
            // A temporary instance is never in the store: the store knows an instance only once `keep` makes it a normal one.
            if (instance?.natural == true) continue
            val record = instance?.let(::record) ?: inert[id]
            if (record != null) {
                put(record)
            } else if (stored.remove(id) != null) {
                store.delete(id)
            }
        }
        dirty.clear()
    }

    private fun put(record: InstanceRecord) {
        stored[record.id] = Placement(record.anchor, record.yaw)
        store.put(record)
    }

    private fun restoreOne(record: InstanceRecord): Instance? {
        val id = record.id
        if (!record.natural) stored[id] = Placement(record.anchor, record.yaw)
        val definition = ResourceRef(record.centity).nameIn(home())?.let { definitions[it] }
        if (definition == null) {
            // A temporary one has nothing worth keeping inert: its entities go.
            if (record.natural) discard(record) else inert[id] = record
            return null
        }
        inert.remove(id)
        val instance = Instance(id, definition, record.anchor, record.yaw)
        instance.natural = record.natural
        instance.displays.putAll(record.displays)
        instance.hitboxes.putAll(record.hitboxes)
        instance.orphans.addAll(record.orphans)
        instances[id] = instance
        obstacles.add(instance)
        own(instance)
        autoplay(instance)
        return instance
    }

    /** A temporary instance whose centity is gone: its entities are removed, as an inert one's are when it's removed. */
    private fun discard(record: InstanceRecord) {
        (record.displays.values + record.hitboxes.values + record.orphans).forEach { platform.entities.remove(it) }
        onRemoved(record.id)
    }

    private fun own(instance: Instance) {
        instance.displays.forEach { (node, entity) -> owners[entity] = instance to node }
        instance.hitboxes.forEach { (node, entity) -> owners[entity] = instance to node }
    }

    private fun autoplay(instance: Instance) {
        for (clip in instance.definition.autoplay) instance.animations.play(clip)
        instance.animate()
    }

    /**
     * Starts every restored instance's script, without `spawn`. One a module
     * spawned while starting already runs its own.
     */
    private fun startAll() {
        for (instance in instances.values.toList()) {
            if (instance.scope == null && !instance.removed) startScripts(instance, fresh = false)
        }
    }

    /**
     * Puts a new instance of [centity] into the world at [at], facing [yaw]
     * (the location's own facing is ignored). Its entities exist, its
     * script's body has run and `spawn` has been heard by the time this
     * returns. Null when there's no such centity or no such world. A
     * [natural] one is temporary (see [Instance.natural]): the spawner makes those.
     */
    fun spawn(centity: String, at: Location, yaw: Double = 0.0, natural: Boolean = false): Instance? {
        val definition = definitions[centity] ?: return null
        if (!platform.worlds.exists(at.world)) return null
        val instance = Instance(UUID.randomUUID(), definition, at.copy(yaw = 0.0, pitch = 0.0), yaw)
        // Before anything is made: its entities are made temporary as they're claimed, and its script's `spawn` can ask.
        instance.natural = natural
        instances[instance.id] = instance
        obstacles.add(instance)
        autoplay(instance)
        sync(instance)
        changed(instance.id)
        startScripts(instance, fresh = true)
        return instance
    }

    /** Runs the centity's script's body; then, for a new instance, `spawn`. */
    private fun startScripts(instance: Instance, fresh: Boolean) {
        val definition = instance.definition
        instance.chunkLoaded = loaded(instance)
        val script = definition.script
        if (script != null) {
            val path = CentityKind.fileOf(definition.id, script.file)
            val source = readSource(path)
            if (source != null) {
                instance.scope = scripts.start(ScopeOwner.CentityScript(instance.id, definition.id, path), script, source).first
                if (instance.removed) return
            }
        }
        if (fresh) emit(Events.CENTITY_SPAWN, instance, CentityEvent(handle(instance)))
    }

    /** Ends an instance's script (its `unload` handlers run) but leaves it in the world. */
    fun stopScripts(instance: Instance) {
        instance.scope?.let(scripts::close)
        instance.scope = null
    }

    private fun stopAll() {
        for (instance in instances.values.toList()) stopScripts(instance)
    }

    /**
     * Takes an instance out of the world for good: `remove`, then its script
     * unload, then every handler on it and its nodes goes, then its entities.
     */
    fun remove(instance: Instance) {
        if (instance.removed || instance.removing) return
        // Still itself while `remove` handlers run; a second remove from one of them does nothing.
        instance.removing = true
        emit(Events.CENTITY_REMOVE, instance, CentityEvent(handle(instance)))
        instance.removed = true
        stopScripts(instance)
        scripts.dropTarget(handle(instance))
        for (entity in instance.entityIds()) {
            owners.remove(entity)
            // An entity in an unloaded chunk is cleaned up as a stray when it loads.
            platform.entities.remove(entity)
        }
        instances.remove(instance.id)
        obstacles.remove(instance)
        onRemoved(instance.id)
        changed(instance.id)
    }

    /** Removes an inert record and whatever of its entities is reachable. */
    fun removeInert(id: UUID): Boolean {
        val record = inert.remove(id) ?: return false
        (record.displays.values + record.hitboxes.values + record.orphans).forEach { platform.entities.remove(it) }
        onRemoved(id)
        changed(id)
        return true
    }

    fun inertRecords(): List<InstanceRecord> = inert.values.toList()

    /** The temporary instances: those that appeared by themselves and haven't been kept. */
    fun naturals(): List<Instance> = instances.values.filter { it.natural && !it.removed }

    /**
     * Makes a natural instance a normal one: its entities are saved with
     * their chunks and the store hears of it at the end of the tick, so it
     * survives a restart. False when it wasn't natural.
     */
    fun keep(instance: Instance): Boolean {
        if (instance.removed || !instance.natural) return false
        instance.natural = false
        for (entity in instance.entityIds()) platform.entities.setPersistent(entity, true)
        changed(instance.id)
        return true
    }

    /** Moves the anchor alone (its nodes already moved back by as much): the store gets it when the world saves. */
    internal fun moveAnchor(instance: Instance, to: Location) {
        instance.anchor = to
    }

    /**
     * Moves the anchor to [to] and, when [yaw] isn't null, turns the centity
     * to face it (the location's own facing is ignored). The store gets it
     * when the world saves.
     */
    fun teleport(instance: Instance, to: Location, yaw: Double? = null): Boolean {
        if (instance.removed || !platform.worlds.exists(to.world)) return false
        // A teleport ends a walk, as `stop_pathing` does: no `path_end`.
        instance.walk = null
        instance.anchor = to.copy(yaw = 0.0, pitch = 0.0)
        if (yaw != null) instance.turnTo(yaw)
        sync(instance)
        return true
    }

    /**
     * Adds a script's node to [instance] under [parent] (a root when null; the
     * caller has checked the name is free and the parent is there). Its
     * entities are spawned by the next sync, in the same tick.
     */
    fun addNode(instance: Instance, node: CompiledCentity.Node) {
        instance.addNode(node)
    }

    /**
     * Removes the script-added node [name] and its descendants from
     * [instance]: their handlers go with them and their entities at the next
     * sync. False when there's no such node.
     */
    fun removeNode(instance: Instance, name: String): Boolean {
        val index = instance.indexOf(name) ?: return false
        val id = instance.id.toString()
        for (gone in instance.removeNode(index)) scripts.dropTarget(LuaHandle.Node(id, gone))
        return true
    }

    /** Turns the centity to face [yaw]: the store gets it when the world saves, so aiming every tick writes nothing. */
    fun turn(instance: Instance, yaw: Double) {
        if (instance.removed) return
        instance.turnTo(yaw)
    }

    /**
     * Moves every instance of [id] onto [next] and restarts its script, or
     * makes them inert when [next] is null (the centity was deleted).
     * Returns how many instances now run the new definition.
     */
    fun reload(id: String, next: CompiledCentity?): Int {
        val live = instances.values.filter { it.centity == id }
        for (instance in live) stopScripts(instance)
        if (next == null) {
            definitions.remove(id)
            for (instance in live) {
                instances.remove(instance.id)
                obstacles.remove(instance)
                instance.entityIds().forEach { owners.remove(it) }
                if (instance.natural) {
                    // Nothing to come back to life for: it was never saved.
                    discard(record(instance))
                    scripts.dropTarget(handle(instance))
                    continue
                }
                inert[instance.id] = record(instance)
                // Inert is not in the world: handles to it answer nil, and its handlers go.
                scripts.dropTarget(handle(instance))
                changed(instance.id)
            }
            return 0
        }
        definitions[id] = next
        for (instance in live) {
            // A node the new definition doesn't have is gone, and so are the handlers on it.
            for (name in instance.nodeNames()) {
                if (next.indexOf(name) == null) scripts.dropTarget(LuaHandle.Node(instance.id.toString(), name))
            }
            instance.redefine(next)
            autoplay(instance)
        }
        val revived = inert.values.filter { ResourceRef(it.centity).nameIn(home()) == id }.mapNotNull { restoreOne(it) }
        val attached = live + revived
        for (instance in attached) {
            sync(instance)
            startScripts(instance, fresh = false)
            changed(instance.id)
        }
        return attached.size
    }

    // ---- per tick --------------------------------------------------------------

    /** Instances whose last tick threw: reported once, not every tick. */
    private val failing = HashSet<UUID>()

    private fun pass() {
        paths.beginTick()
        for (instance in instances.values.toList()) {
            if (instance.removed) continue
            val loaded = loaded(instance)
            val was = instance.chunkLoaded
            if (was != null && was != loaded) {
                instance.chunkLoaded = loaded
                emit(if (loaded) Events.CENTITY_CHUNK_LOAD else Events.CENTITY_CHUNK_UNLOAD, instance, CentityEvent(handle(instance)))
            }
            if (instance.removed || !loaded) continue
            // One instance's bug (ours, or an adapter's) mustn't stop the others.
            try {
                tick(instance)
                failing.remove(instance.id)
            } catch (e: Exception) {
                if (failing.add(instance.id)) log.error("Centity ${instance.centity} (${instance.id}) failed to tick", e)
            }
        }
    }

    private fun tick(instance: Instance) {
        instance.age++
        if (instance.animations.active) {
            val ended = instance.animations.advance(SECONDS_PER_TICK)
            instance.animate()
            for (clip in ended) {
                if (instance.removed) break
                emit(Events.CENTITY_ANIMATION_END, instance, AnimationEvent(handle(instance), clip))
            }
        }
        // Walking moves the whole centity first, so physics settles its bodies where it has been walked to.
        if (instance.walk != null && !instance.removed) paths.step(instance)
        if (instance.hasBodies && !instance.removed) physics.step(instance, SECONDS_PER_TICK)
        // Rates are handler options (`every`), counted by the event core; nobody listening, no tick.
        if (!instance.removed) emit(Events.CENTITY_TICK, instance, TickEvent(scripts.now))
        if (!instance.removed) sync(instance)
    }

    /**
     * Whether the instance is in the world right now: the entities of its
     * anchor's chunk are loaded (its displays stand at the anchor, so then
     * they're either reachable or gone; see [dropGone]). One with no entities
     * at all (just spawned, or every one of them gone) always is, so
     * [reconcile] can put them in the world, wherever that is.
     */
    fun loaded(instance: Instance): Boolean =
        (instance.displays.isEmpty() && instance.hitboxes.isEmpty()) || platform.worlds.entitiesLoaded(instance.anchor)

    // ---- sync ------------------------------------------------------------------

    /** Brings the instance's entities in line with its state, writing only what changed. */
    fun sync(instance: Instance) {
        if (instance.removed || !loaded(instance)) return
        dropGone(instance)
        if (instance.needsReconcile) reconcile(instance)
        sweepOrphans(instance)
        if (!instance.poseDirty && !instance.forceSync && !instance.moved) return

        val world = instance.placed()
        val cull = cullSize(world)
        val anchor = instance.anchor
        val entities = platform.entities
        for ((index, node) in instance.definition.nodes.withIndex()) {
            val shown = instance.shown(index)
            instance.displays[node.name]?.let { entity ->
                val display = instance.display(index)
                val contentChanged = instance.contentChanged(index)
                if (contentChanged && display != null && !entities.updateDisplay(entity, display)) {
                    instance.needsReconcile = true
                }
                // After the content, which a look overrides (a billboard, an item), and before a teleport it eases.
                val look = instance.shownLook(index)
                if (instance.forceSync || contentChanged || instance.pushedLooks[entity] != look) {
                    entities.setLook(entity, look)
                    instance.pushedLooks[entity] = look
                }
                if (instance.moved) entities.teleport(entity, anchor)
                val matrix = if (shown) world[index] else HIDDEN
                val pushed = Instance.PushedPose(matrix.values.toList(), cull)
                if (instance.forceSync || instance.pushedPoses[entity] != pushed) {
                    entities.setPose(entity, DisplayPose(matrix, if (instance.forceSync) 0 else instance.interpolation(index), cull))
                    instance.pushedPoses[entity] = pushed
                }
            }
            instance.hitboxes[node.name]?.let { entity ->
                // An unclickable hitbox is collapsed like a hidden one, so clicks pass it by.
                val bounds = if (shown && instance.clickable(index)) {
                    Hitboxes.bounds(boxesOf(instance, index), world[index])
                } else {
                    val origin = world[index].translation()
                    Hitboxes.Bounds(origin.x, origin.y, origin.z, Hitboxes.MIN_SIZE, Hitboxes.MIN_SIZE)
                }
                if (instance.forceSync || instance.moved || instance.pushedHitboxes[entity] != bounds) {
                    entities.teleport(entity, anchor.offset(bounds.centerX, bounds.bottomY, bounds.centerZ))
                    entities.resizeHitbox(entity, bounds.width, bounds.height)
                    instance.pushedHitboxes[entity] = bounds
                }
            }
        }
        instance.markSynced()
        // A display that turned out to be the wrong kind is respawned on the next pass.
        if (instance.needsReconcile) sync(instance)
    }

    /**
     * Makes the entities match the definition: removes ones whose node or
     * component is gone, updates every display's content (respawning one
     * whose kind changed), spawns what's missing.
     */
    private fun reconcile(instance: Instance) {
        val definition = instance.definition
        for ((name, entity) in instance.displays.toList()) {
            val index = definition.indexOf(name)
            if (index == null || instance.display(index) == null) {
                instance.displays.remove(name)
                retire(instance, entity)
            }
        }
        for ((name, entity) in instance.hitboxes.toList()) {
            if (definition.node(name)?.hitbox == null) {
                instance.hitboxes.remove(name)
                retire(instance, entity)
            }
        }

        val world = instance.placed()
        val cull = cullSize(world)
        val anchor = instance.anchor
        val entities = platform.entities
        for ((index, node) in definition.nodes.withIndex()) {
            val display = instance.display(index)
            if (display != null) {
                val existing = instance.displays[node.name]
                if (existing != null && !entities.updateDisplay(existing, display)) {
                    instance.displays.remove(node.name)
                    retire(instance, existing)
                }
                if (instance.displays[node.name] == null) {
                    val matrix = if (instance.shown(index)) world[index] else HIDDEN
                    entities.spawnDisplay(
                        anchor,
                        display,
                        DisplayPose(matrix, 0, cull),
                        EntityTag(instance.id, node.name, EntityRole.DISPLAY)
                    )
                        ?.let { claim(instance, node.name, it, instance.displays) }
                }
            }
            if (node.hitbox != null && instance.hitboxes[node.name] == null) {
                val bounds = Hitboxes.bounds(boxesOf(instance, index), world[index])
                entities.spawnHitbox(
                    anchor.offset(bounds.centerX, bounds.bottomY, bounds.centerZ),
                    bounds.width,
                    bounds.height,
                    EntityTag(instance.id, node.name, EntityRole.HITBOX)
                )?.let { claim(instance, node.name, it, instance.hitboxes) }
            }
        }
        instance.needsReconcile = false
        instance.forceSync = true
        changed(instance.id)
    }

    private fun claim(instance: Instance, node: String, entity: UUID, into: MutableMap<String, UUID>) {
        into[node] = entity
        owners[entity] = instance to node
        // A temporary instance's entities aren't saved with their chunk, so a crash can't leave them behind.
        if (instance.natural) platform.entities.setPersistent(entity, false)
        for (player in instance.hiddenFrom) platform.entities.setHidden(player, entity, true)
    }

    // ---- per-player visibility ---------------------------------------------------

    /**
     * Hides [instance] from [player] (every display and hitbox), or shows it
     * again. The record is ours: the server forgets when the player leaves or
     * the chunk unloads, and [playerJoined] and [entitiesLoaded] say it again.
     */
    fun setHidden(instance: Instance, player: UUID, hidden: Boolean) {
        if (instance.removed) return
        val changed = if (hidden) instance.hiddenFrom.add(player) else instance.hiddenFrom.remove(player)
        if (!changed) return
        for (entity in instance.displays.values + instance.hitboxes.values) platform.entities.setHidden(player, entity, hidden)
    }

    /** A player joined: everything hidden from them is hidden again, since the server forgot. */
    override fun playerJoined(player: PlayerRef) {
        for (instance in instances.values) {
            if (player.uuid !in instance.hiddenFrom) continue
            for (entity in instance.displays.values + instance.hitboxes.values) platform.entities.setHidden(player.uuid, entity, true)
        }
    }

    private fun retire(instance: Instance, entity: UUID) {
        forget(entity, instance)
        if (!platform.entities.remove(entity)) instance.orphans += entity
    }

    /**
     * Forgets entities that can't be reached although their chunk's entities
     * are loaded: something else killed or removed them. [reconcile] spawns
     * them again. (A display stands at the anchor; a hitbox where it was last
     * put, which may be the next chunk over.)
     */
    private fun dropGone(instance: Instance) {
        val entities = platform.entities
        fun gone(entity: UUID, at: Location) = !entities.isLoaded(entity) && platform.worlds.entitiesLoaded(at)
        val anchor = instance.anchor
        val goneDisplays = instance.displays.filterValues { gone(it, anchor) }
        val goneHitboxes = instance.hitboxes.filterValues { entity ->
            val at = instance.pushedHitboxes[entity]?.let { anchor.offset(it.centerX, it.bottomY, it.centerZ) } ?: anchor
            gone(entity, at)
        }
        if (goneDisplays.isEmpty() && goneHitboxes.isEmpty()) return
        for ((name, entity) in goneDisplays) {
            instance.displays.remove(name)
            forget(entity, instance)
        }
        for ((name, entity) in goneHitboxes) {
            instance.hitboxes.remove(name)
            forget(entity, instance)
        }
        instance.needsReconcile = true
        changed(instance.id)
    }

    private fun forget(entity: UUID, instance: Instance) {
        owners.remove(entity)
        instance.pushedPoses.remove(entity)
        instance.pushedHitboxes.remove(entity)
        instance.pushedLooks.remove(entity)
    }

    /**
     * Orphans (entities of ours we couldn't remove when they stopped being
     * wanted) are tried once the instance is loaded, then forgotten: one that
     * still can't be reached is gone, or in an unloaded neighbouring chunk,
     * where [entitiesLoaded] removes it as a stray when that chunk loads.
     */
    private fun sweepOrphans(instance: Instance) {
        if (instance.orphans.isEmpty()) return
        instance.orphans.forEach { platform.entities.remove(it) }
        instance.orphans.clear()
        changed(instance.id)
    }

    /** The boxes a node's hitbox is made of right now, in node space. */
    fun boxesOf(instance: Instance, index: Int): List<Box> {
        val hitbox = instance.definition.nodes[index].hitbox ?: return Hitboxes.UNIT_CUBE
        hitbox.boxes?.let { return it }
        if (hitbox.followsCollision) {
            val state = (instance.display(index) as? BlockDisplay)?.let { BlockState.parse(it.block) }
            if (state != null) {
                val game = platform.game
                val full = game.block(state.id)?.let { state.withDefaults(it) } ?: state
                game.collisionBoxes(full)?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return Hitboxes.UNIT_CUBE
    }

    /** Twice the furthest node offset, rounded up so a moving centity doesn't resend it every tick. */
    private fun cullSize(world: List<Matrix4>): Double {
        var furthest = 1.0
        for (matrix in world) {
            val t = matrix.translation()
            furthest = maxOf(furthest, sqrt(t.x * t.x + t.y * t.y + t.z * t.z))
        }
        return ceil(furthest + 2) * 2
    }

    // ---- world events ----------------------------------------------------------

    /**
     * Entities came back with their chunk. Ours are resynced in full (the
     * client may hold anything); tagged ones nobody owns are strays from a
     * removed instance or a lost record, and go.
     */
    override fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) {
        for ((entity, tag) in tagged) {
            val owner = owners[entity]
            if (owner != null) {
                owner.first.forceSync = true
                // The server forgets who an entity was hidden from when it unloads.
                for (player in owner.first.hiddenFrom) platform.entities.setHidden(player, entity, true)
                continue
            }
            val known = instances[tag.instance]?.orphans?.contains(entity) == true || tag.instance in inert
            if (!known) platform.entities.remove(entity)
        }
    }

    /**
     * Routes a click on one of our entities. True when the entity is ours,
     * whether or not any script wanted the click.
     *
     * When the player's line of sight is known, the click goes to the nearest
     * node it actually hits, not just the entity the client picked: a node
     * whose box sits in front wins over a sibling whose padding is nearer, and
     * a click through the padding of a `raycast` node reaches nothing.
     */
    fun click(entity: UUID, player: PlayerRef, button: ClickButton, sight: Ray?): Boolean {
        val (instance, name) = owners[entity] ?: return false
        // The server keeps a hidden interaction from being clicked; this says so for any platform.
        if (instance.removed || player.uuid in instance.hiddenFrom) return true
        val clicked = instance.indexOf(name) ?: return true
        val target = pick(instance, clicked, sight) ?: return true
        // `spawning.keepOnInteract`: a click that reaches a node keeps a natural one, before scripts hear it.
        if (instance.natural && instance.definition.spawning?.keepOnInteractOrDefault == true) keep(instance)
        dispatchClick(instance, target, player, button)
        return true
    }

    /**
     * Where a player would aim to click [instance]'s node [node] (its first
     * clickable, shown node with a hitbox when null): the node's interaction
     * entity, and the middle of its box in the world. Null when that node
     * has no hitbox out in the world to click.
     */
    fun aim(instance: Instance, node: String?): Pair<UUID, Vec3>? {
        val name = node ?: instance.definition.nodes.withIndex().firstOrNull { (index, it) ->
            it.hitbox != null && instance.shown(index) && instance.clickable(index) && it.name in instance.hitboxes
        }?.value?.name ?: return null
        val entity = instance.hitboxes[name] ?: return null
        val bounds = instance.pushedHitboxes[entity] ?: return entity to instance.atAnchor(Vec3(0.0, 0.0, 0.0))
        return entity to instance.atAnchor(Vec3(bounds.centerX, bounds.bottomY + bounds.height / 2, bounds.centerZ))
    }

    /**
     * The node a click lands on, with where the line of sight met it and the
     * face's normal, in the world (null when there's no line of sight to go by).
     */
    private data class Picked(val index: Int, val position: Vec3? = null, val normal: Vec3? = null)

    private fun pick(instance: Instance, clicked: Int, sight: Ray?): Picked? {
        if (sight == null || sight.world != instance.anchor.world) return if (instance.clickable(clicked)) Picked(clicked) else null
        // Relative to the anchor along the world's axes, where the entities are shown.
        val origin = instance.fromAnchor(Vec3(sight.x, sight.y, sight.z))
        val direction = Vec3(sight.dx, sight.dy, sight.dz)
        val best = nearestHitbox(instance, origin, direction, REACH)
        if (best != null) {
            // Back into the world: the ray was relative to the anchor.
            val (index, hit) = best
            return Picked(index, instance.atAnchor(origin + direction * hit.distance), hit.normal)
        }
        // A node without the narrow phase is its interaction entity, so a ray
        // that missed it is a stale look vector, not a miss.
        val node = instance.definition.nodes[clicked]
        return if (node.hitbox?.raycastEnabled == true || !instance.clickable(clicked)) null else Picked(clicked)
    }

    /**
     * The node of [instance] whose hitbox a ray hits first within
     * [maxDistance], with the hit: [origin] relative to the anchor along the
     * world's axes, [direction] a unit vector. A `raycast` node is tested
     * against its boxes, any other against the interaction entity's bounds:
     * the shapes a click is picked by. A node that isn't clickable is skipped.
     */
    private fun nearestHitbox(instance: Instance, origin: Vec3, direction: Vec3, maxDistance: Double): Pair<Int, Hitboxes.Hit>? {
        val world = instance.placed()
        var best: Pair<Int, Hitboxes.Hit>? = null
        for ((index, node) in instance.definition.nodes.withIndex()) {
            val hitbox = node.hitbox ?: continue
            if (!instance.shown(index) || !instance.clickable(index) || index !in world.indices) continue
            val boxes = boxesOf(instance, index)
            val hit = if (hitbox.raycastEnabled) {
                Hitboxes.hit(boxes, world[index], origin, direction, maxDistance)
            } else {
                Hitboxes.hit(Hitboxes.bounds(boxes, world[index]), origin, direction, maxDistance)
            } ?: continue
            if (best == null || hit.distance < best.second.distance) best = index to hit
        }
        return best
    }

    /** What a ray hit among the centities: [instance]'s node [index], where (in the world), the face's normal and how far along. */
    data class HitboxHit(val instance: Instance, val index: Int, val position: Vec3, val normal: Vec3, val distance: Double)

    /**
     * The first centity hitbox a ray in [world] from [origin] along the unit
     * [direction] hits within [maxDistance], tested with the shapes clicks
     * are; instances whose chunk isn't loaded aren't hit.
     */
    fun raycast(world: String, origin: Vec3, direction: Vec3, maxDistance: Double): HitboxHit? {
        var best: HitboxHit? = null
        for (instance in instances.values) {
            if (instance.removed || instance.anchor.world != world || !loaded(instance)) continue
            val from = instance.fromAnchor(origin)
            val (index, hit) = nearestHitbox(instance, from, direction, best?.distance ?: maxDistance) ?: continue
            if (best != null && hit.distance >= best.distance) continue
            best = HitboxHit(instance, index, instance.atAnchor(from + direction * hit.distance), hit.normal, hit.distance)
        }
        return best
    }

    /**
     * A click along its path: the clicked node, each node above it, the
     * centity, then `nf.on("centity_click")`, until a handler stops it.
     */
    private fun dispatchClick(instance: Instance, picked: Picked, player: PlayerRef, button: ClickButton) {
        val nodes = instance.definition.nodes
        val id = instance.id.toString()
        val path = mutableListOf<Pair<EventType<ClickEvent>, LuaHandle?>>()
        var index = picked.index
        while (index >= 0) {
            path += Events.NODE_CLICK to LuaHandle.Node(id, nodes[index].name)
            index = nodes[index].parentIndex
        }
        path += Events.CENTITY_CLICK to handle(instance)
        path += Events.NF_CENTITY_CLICK to null
        val event = ClickEvent(
            player = LuaHandle.Player(player.uuid.toString()),
            target = LuaHandle.Node(id, nodes[picked.index].name),
            click = button.luaName,
            hitPosition = picked.position,
            hitNormal = picked.normal
        )
        scripts.emit(path, event)
    }

    // ---- events ----------------------------------------------------------------

    private fun handle(instance: Instance) = LuaHandle.Centity(instance.id.toString())

    /** Raises one of a centity's own events on it. */
    private fun <P : LuaEvent> emit(event: EventType<P>, instance: Instance, payload: P) {
        scripts.emit(event, handle(instance), payload)
    }

    /** `play_animation` started [animation] on [instance]. */
    fun animationStarted(instance: Instance, animation: String) {
        emit(Events.CENTITY_ANIMATION_START, instance, AnimationEvent(handle(instance), animation))
    }

    /** [instance]'s walk ended: [CentityPaths] has already let go of it, so a handler can send it off again. */
    internal fun pathEnded(instance: Instance, reached: Boolean) {
        emit(Events.CENTITY_PATH_END, instance, CentityPathEndEvent(handle(instance), reached))
    }

    /** Whether anything listens for [event] on node [index] of [instance]: physics asks before building a payload. */
    fun listening(instance: Instance, index: Int, event: EventType<*>): Boolean =
        scripts.listening(LuaHandle.Node(instance.id.toString(), instance.definition.nodes[index].name), event)

    /** Raises one of a node's events, for [Physics]. */
    fun <P : LuaEvent> emitNode(event: EventType<P>, instance: Instance, index: Int, payload: P) {
        scripts.emit(event, LuaHandle.Node(instance.id.toString(), instance.definition.nodes[index].name), payload)
    }

    // ---- records ---------------------------------------------------------------

    /** [instance] as the store keeps it: its centity named in full. */
    fun record(instance: Instance) = InstanceRecord(
        id = instance.id,
        centity = ResourceRef(instance.centity).resolve(home())?.toString() ?: instance.centity,
        anchor = instance.anchor,
        yaw = instance.yaw,
        displays = instance.displays.toMap(),
        hitboxes = instance.hitboxes.toMap(),
        orphans = instance.orphans.toList(),
        natural = instance.natural
    )

    fun records(): List<InstanceRecord> = instances.values.map(::record) + inert.values

    /** The centity an inert record names, as the project names it (bare for its own). */
    fun centityOf(record: InstanceRecord): String = ResourceRef(record.centity).nameIn(home()) ?: record.centity

    fun info(): List<InstanceInfo> = instances.values.map(::info) + inert.values.map {
        InstanceInfo(it.id.toString(), centityOf(it), it.anchor.world, it.anchor.x, it.anchor.y, it.anchor.z)
    }

    fun info(instance: Instance): InstanceInfo = with(instance.anchor) {
        InstanceInfo(instance.id.toString(), instance.centity, world, x, y, z)
    }

    companion object {
        const val SECONDS_PER_TICK = 0.05

        /** How far a click may reach for the narrow phase; past any reach the server allows. */
        const val REACH = 8.0

        /** Displays can't be hidden, only collapsed. */
        private val HIDDEN = Matrix4().scale(0.0, 0.0, 0.0)
    }
}
