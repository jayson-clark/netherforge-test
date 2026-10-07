package dev.netherforge.plugin.api

import dev.netherforge.format.Vec3
import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.Channel
import dev.netherforge.format.centity.ItemDisplay
import dev.netherforge.format.centity.LoopMode
import dev.netherforge.format.centity.TextAlignment
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.degreesToRadians
import dev.netherforge.format.math.radiansToDegrees
import dev.netherforge.plugin.centity.Centities
import dev.netherforge.plugin.centity.CentityPaths
import dev.netherforge.plugin.centity.Instance
import dev.netherforge.plugin.centity.PathSettings
import dev.netherforge.plugin.centity.PathTarget
import dev.netherforge.plugin.centity.Physics
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.physics.BodyState
import dev.netherforge.plugin.physics.Quat
import dev.netherforge.plugin.platform.DisplayBrightness
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The live instance a handle names, or null once it's removed. */
private fun ProjectSession.instance(self: LuaHandle.Centity): Instance? = centities.find(self.id)?.takeIf { !it.removed }

/** Adds a node a script described under [parent] (a root when null) and returns its handle; the name must be free. */
private fun ProjectSession.addNode(instance: Instance, name: String, parent: Int?, definition: LuaValue?): LuaHandle.Node {
    val node = nodeFrom(name, parent ?: -1, definition, platform.game)
    if (instance.indexOf(name) != null) throw LuaApiException("bad argument 'name' (the centity already has a node called \"$name\")")
    centities.addNode(instance, node)
    return LuaHandle.Node(instance.id.toString(), name)
}

internal class CentityImpl(private val session: ProjectSession) : CentityApi {
    private val centities get() = session.centities

    private fun instance(self: LuaHandle.Centity) = session.instance(self)

    override fun id(self: LuaHandle.Centity): String = self.id

    override fun kind(self: LuaHandle.Centity): String? = instance(self)?.centity?.let(session.names::spell)

    override fun exists(self: LuaHandle.Centity): Boolean = instance(self) != null

    override fun isNatural(self: LuaHandle.Centity): Boolean = instance(self)?.natural == true

    override fun keep(self: LuaHandle.Centity): Boolean = instance(self)?.let(centities::keep) == true

    override fun data(self: LuaHandle.Centity): Any? {
        val instance = instance(self) ?: return null
        return session.data.table(ScriptData.Owner.Centity(instance.id), "centity ${instance.centity} ${instance.id}")
    }

    override fun node(self: LuaHandle.Centity, name: String): LuaHandle.Node? =
        instance(self)?.indexOf(name)?.let { LuaHandle.Node(self.id, name) }

    override fun location(self: LuaHandle.Centity): LuaLocation? =
        instance(self)?.let { LuaLocation(LuaHandle.World(it.anchor.world), Vec3(it.anchor.x, it.anchor.y, it.anchor.z), it.yaw) }

    override fun position(self: LuaHandle.Centity): Vec3? = instance(self)?.anchor?.let { Vec3(it.x, it.y, it.z) }

    override fun world(self: LuaHandle.Centity): LuaHandle.World? = instance(self)?.anchor?.world?.let { LuaHandle.World(it) }

    override fun yaw(self: LuaHandle.Centity): Double? = instance(self)?.yaw

    override fun setYaw(self: LuaHandle.Centity, degrees: Double) {
        instance(self)?.let { centities.turn(it, degrees) }
    }

    override fun lookAt(self: LuaHandle.Centity, point: Vec3): Boolean {
        val instance = instance(self) ?: return false
        val toward = instance.fromAnchor(point)
        // Straight above or below, any facing is as good as another: keep this one.
        if (toward.x * toward.x + toward.z * toward.z < 1e-18) return true
        centities.turn(instance, radiansToDegrees(atan2(-toward.x, toward.z)))
        return true
    }

    override fun toLocal(self: LuaHandle.Centity, point: Vec3): Vec3? = instance(self)?.toCentity(point)

    override fun toWorld(self: LuaHandle.Centity, point: Vec3): Vec3? = instance(self)?.toWorld(point)

    override fun teleport(self: LuaHandle.Centity, locationOrPosition: LocationOrVec3): Boolean {
        val instance = instance(self) ?: return false
        val place = locationOrPosition.place
        // The location's facing turns the centity; a bare position, or a location with no facing, keeps its own.
        return centities.teleport(instance, place.resolve(instance.anchor.world), place.yaw)
    }

    override fun moveTo(self: LuaHandle.Centity, target: LocationOrVec3OrEntityOrCentity, options: CentityPathOptions?): Boolean {
        val settings = pathSettings(options)
        val instance = instance(self) ?: return false
        fun place(place: LuaPlace): PathTarget? {
            val at = place.resolve(instance.anchor.world)
            return if (at.world == instance.anchor.world) PathTarget.Place(Vec3(at.x, at.y, at.z)) else null
        }
        val goal = when (target) {
            is LocationOrVec3OrEntityOrCentity.Location -> place(LuaPlace.of(target.value))
            is LocationOrVec3OrEntityOrCentity.Vec3 -> place(LuaPlace(null, target.value, null, null))
            is LocationOrVec3OrEntityOrCentity.Centity -> runCatching {
                UUID.fromString(target.value.id)
            }.getOrNull()?.let(PathTarget::Centity)
            is LocationOrVec3OrEntityOrCentity.Entity -> target.value.uuidOrNull()?.let(PathTarget::Entity)
        } ?: return false
        return centities.paths.start(instance, goal, settings)
    }

    /** `move_to`'s options, checked: a value out of range is an error. */
    private fun pathSettings(options: CentityPathOptions?): PathSettings {
        fun check(field: String, value: Double?, ok: (Double) -> Boolean, what: String): Double? {
            if (value != null && (!value.isFinite() || !ok(value))) throw LuaApiException("options.$field must be $what, not $value")
            return value
        }
        val speed =
            check("speed", options?.speed, {
                it > 0 && it <= CentityPaths.MAX_SPEED
            }, "more than 0 and at most ${CentityPaths.MAX_SPEED.toInt()}")
        val width =
            check("width", options?.width, {
                it > 0 && it <= CentityPaths.MAX_WIDTH
            }, "more than 0 and at most ${CentityPaths.MAX_WIDTH.toInt()}")
        val height =
            check("height", options?.height, {
                it > 0 && it <= CentityPaths.MAX_HEIGHT
            }, "more than 0 and at most ${CentityPaths.MAX_HEIGHT.toInt()}")
        val step = check("step_height", options?.stepHeight, { it >= 0 }, "at least 0")
        val drop = check("max_drop", options?.maxDrop, { it >= 0 }, "at least 0")
        val range =
            check("range", options?.range, {
                it > 0 && it <= CentityPaths.MAX_RANGE
            }, "more than 0 and at most ${CentityPaths.MAX_RANGE.toInt()}")
        return PathSettings(
            speed = speed ?: CentityPaths.DEFAULT_SPEED,
            fly = options?.fly == true,
            face = options?.face != false,
            width = width,
            height = height,
            stepHeight = step ?: CentityPaths.DEFAULT_STEP_HEIGHT,
            maxDrop = drop ?: CentityPaths.DEFAULT_MAX_DROP,
            range = range ?: CentityPaths.DEFAULT_RANGE
        )
    }

    override fun stopPathing(self: LuaHandle.Centity): Boolean = instance(self)?.let { centities.paths.stop(it) } == true

    override fun hasPath(self: LuaHandle.Centity): Boolean = instance(self)?.walk != null

    override fun pathTarget(self: LuaHandle.Centity): Vec3? = instance(self)?.let { centities.paths.pathEnd(it) }

    override fun remove(self: LuaHandle.Centity) {
        instance(self)?.let { centities.remove(it) }
    }

    override fun addNode(self: LuaHandle.Centity, name: String, definition: LuaValue?): LuaHandle.Node? {
        val instance = instance(self) ?: return null
        return session.addNode(instance, name, null, definition)
    }

    override fun nodes(self: LuaHandle.Centity): List<LuaHandle.Node> =
        instance(self)?.nodeNames().orEmpty().map { LuaHandle.Node(self.id, it) }

    override fun roots(self: LuaHandle.Centity): List<LuaHandle.Node> =
        instance(self)?.definition?.nodes.orEmpty().filter { it.isRoot }.map { LuaHandle.Node(self.id, it.name) }

    override fun playAnimation(self: LuaHandle.Centity, name: String, options: AnimationOptions?): Boolean {
        val speed = options?.speed ?: 1.0
        if (!speed.isFinite() || speed < 0) throw LuaApiException("bad field 'options.speed' (a speed can't be negative)")
        val from = options?.fromTick ?: 0.0
        if (!from.isFinite() || from < 0) throw LuaApiException("bad field 'options.from_tick' (a tick can't be negative)")
        val blend = options?.blendTicks ?: 0L
        if (blend < 0) throw LuaApiException("bad field 'options.blend_ticks' (ticks can't be negative)")
        val instance = instance(self) ?: return false
        val clip = clip(instance, name)
        val loop = when (options?.loop) {
            true -> LoopMode.LOOP
            false -> if (clip.loop == LoopMode.LOOP) LoopMode.ONCE else clip.loop
            null -> null
        }
        instance.animations.play(
            clip,
            speed = speed,
            from = from * Centities.SECONDS_PER_TICK,
            loop = loop,
            blendSeconds = blend * Centities.SECONDS_PER_TICK,
            pose = instance.shownPose()
        )
        instance.animate()
        centities.animationStarted(instance, name)
        return true
    }

    /** The clip called [name]; one the centity doesn't have is an error. */
    private fun clip(instance: Instance, name: String) =
        instance.definition.animation(name) ?: throw LuaApiException(noAnimation(instance, name))

    override fun pauseAnimation(self: LuaHandle.Centity, name: String): Boolean {
        val instance = instance(self) ?: return false
        clip(instance, name)
        return instance.animations.pause(name)
    }

    override fun resumeAnimation(self: LuaHandle.Centity, name: String): Boolean {
        val instance = instance(self) ?: return false
        clip(instance, name)
        return instance.animations.resume(name)
    }

    override fun animationTick(self: LuaHandle.Centity, name: String): Double? {
        val instance = instance(self) ?: return null
        clip(instance, name)
        return instance.animations.position(name)?.let { it / Centities.SECONDS_PER_TICK }
    }

    override fun seekAnimation(self: LuaHandle.Centity, name: String, tick: Double): Boolean {
        if (!tick.isFinite() || tick < 0) throw LuaApiException("bad argument 'tick' (a tick can't be negative)")
        val instance = instance(self) ?: return false
        clip(instance, name)
        if (!instance.animations.seek(name, tick * Centities.SECONDS_PER_TICK)) return false
        instance.animate()
        return true
    }

    override fun animationSpeed(self: LuaHandle.Centity, name: String): Double? {
        val instance = instance(self) ?: return null
        clip(instance, name)
        return instance.animations.speed(name)
    }

    override fun setAnimationSpeed(self: LuaHandle.Centity, name: String, multiplier: Double): Boolean {
        if (!multiplier.isFinite() || multiplier < 0) throw LuaApiException("bad argument 'multiplier' (a speed can't be negative)")
        val instance = instance(self) ?: return false
        clip(instance, name)
        return instance.animations.setSpeed(name, multiplier)
    }

    // ---- looks, over every node --------------------------------------------------

    /** Indices of the nodes that draw something. */
    private fun Instance.drawn(): List<Int> = definition.nodes.indices.filter { display(it) != null }

    /** Indices of the block and item displays: the ones a glow colour shows on. */
    private fun Instance.coloured(): List<Int> = drawn().filter { display(it) !is TextDisplay }

    override fun isGlowing(self: LuaHandle.Centity): Boolean {
        val instance = instance(self) ?: return false
        val drawn = instance.drawn()
        return drawn.isNotEmpty() && drawn.all { instance.look(it).glowing }
    }

    override fun setGlowing(self: LuaHandle.Centity, glowing: Boolean): Boolean {
        val instance = instance(self) ?: return false
        for (index in instance.drawn()) instance.setLook(index, instance.look(index).copy(glowing = glowing))
        return true
    }

    override fun glowColor(self: LuaHandle.Centity): String? {
        val instance = instance(self) ?: return null
        val colours = instance.coloured().map { instance.look(it).glowColor }.distinct()
        return colours.singleOrNull()?.let(::formatRgb)
    }

    override fun setGlowColor(self: LuaHandle.Centity, color: String?): Boolean {
        val rgb = color?.let { parseRgb(it, "color") }
        val instance = instance(self) ?: return false
        val coloured = instance.coloured()
        for (index in coloured) instance.setLook(index, instance.look(index).copy(glowColor = rgb))
        return coloured.isNotEmpty()
    }

    // ---- who sees it ---------------------------------------------------------------

    private fun playerId(player: LuaHandle.Player) = UUID.fromString(player.id)

    override fun hideFrom(self: LuaHandle.Centity, player: LuaHandle.Player): Boolean {
        val instance = instance(self) ?: return false
        centities.setHidden(instance, playerId(player), true)
        return true
    }

    override fun showTo(self: LuaHandle.Centity, player: LuaHandle.Player): Boolean {
        val instance = instance(self) ?: return false
        centities.setHidden(instance, playerId(player), false)
        return true
    }

    override fun isHiddenFrom(self: LuaHandle.Centity, player: LuaHandle.Player): Boolean =
        instance(self)?.hiddenFrom?.contains(playerId(player)) == true

    override fun viewRange(self: LuaHandle.Centity): Double? = instance(self)?.viewRange

    override fun setViewRange(self: LuaHandle.Centity, blocks: Double): Boolean {
        if (!blocks.isFinite() || blocks < 0) throw LuaApiException("bad argument 'blocks' (a view range can't be negative)")
        val instance = instance(self) ?: return false
        instance.viewRange = blocks
        return true
    }

    private fun noAnimation(instance: Instance, name: String): String {
        val known = instance.definition.animations.map { it.name }
        return "${instance.centity} has no animation \"$name\"" + if (known.isEmpty()) "" else " (it has: ${known.joinToString()})"
    }

    override fun stopAnimation(self: LuaHandle.Centity, name: String?) {
        val instance = instance(self) ?: return
        if (name == null) instance.animations.stopAll() else instance.animations.stop(name)
        instance.animate()
    }

    override fun isAnimationPlaying(self: LuaHandle.Centity, name: String): Boolean = instance(self)?.animations?.isPlaying(name) == true

    override fun playingAnimations(self: LuaHandle.Centity): List<String> = instance(self)?.animations?.names().orEmpty()

    override fun animations(self: LuaHandle.Centity): List<String> = instance(self)?.definition?.animations?.map { it.name }.orEmpty()
}

internal class NodeImpl(private val session: ProjectSession) : NodeApi {
    override fun name(self: LuaHandle.Node): String = self.name

    override fun centity(self: LuaHandle.Node): LuaHandle.Centity = LuaHandle.Centity(self.centity)

    /** The node's instance and index, or null once its centity is removed. */
    private fun node(self: LuaHandle.Node): Pair<Instance, Int>? {
        val instance = session.instance(LuaHandle.Centity(self.centity)) ?: return null
        val index = instance.indexOf(self.name) ?: return null
        return instance to index
    }

    override fun parent(self: LuaHandle.Node): LuaHandle.Node? {
        val (instance, index) = node(self) ?: return null
        val parent = instance.definition.nodes[index].parentIndex
        return if (parent < 0) null else LuaHandle.Node(self.centity, instance.definition.nodes[parent].name)
    }

    override fun addChild(self: LuaHandle.Node, name: String, definition: LuaValue?): LuaHandle.Node? {
        val (instance, index) = node(self) ?: return null
        return session.addNode(instance, name, index, definition)
    }

    override fun remove(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        if (!instance.isAdded(index)) {
            throw LuaApiException("node \"${self.name}\" is declared by centity.json; only nodes a script added can be removed")
        }
        return session.centities.removeNode(instance, self.name)
    }

    override fun children(self: LuaHandle.Node): List<LuaHandle.Node> {
        val (instance, index) = node(self) ?: return emptyList()
        return instance.definition.nodes.filter { it.parentIndex == index }.map { LuaHandle.Node(self.centity, it.name) }
    }

    private fun get(self: LuaHandle.Node, channel: Channel): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.get(index, channel)
    }

    private fun set(self: LuaHandle.Node, channel: Channel, value: Vec3) {
        val (instance, index) = node(self) ?: return
        instance.set(index, channel, value)
    }

    override fun translation(self: LuaHandle.Node) = get(self, Channel.TRANSLATION)

    override fun rotation(self: LuaHandle.Node) = get(self, Channel.ROTATION)

    override fun scale(self: LuaHandle.Node) = get(self, Channel.SCALE)

    override fun setTranslation(self: LuaHandle.Node, translation: Vec3) = set(self, Channel.TRANSLATION, translation)

    override fun setRotation(self: LuaHandle.Node, rotation: Vec3) = set(self, Channel.ROTATION, rotation)

    override fun setScale(self: LuaHandle.Node, scale: Vec3) = set(self, Channel.SCALE, scale)

    // ---- coordinate spaces: through Instance.worldMatrix, so as shown now ----------

    override fun worldPosition(self: LuaHandle.Node): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.worldMatrix(index).translation()
    }

    override fun worldRotation(self: LuaHandle.Node): Vec3? {
        val (instance, index) = node(self) ?: return null
        return Quat.fromBasis(instance.worldMatrix(index)).toEuler()
    }

    override fun toLocal(self: LuaHandle.Node, point: Vec3): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.worldMatrix(index).invertAffine()?.transformPosition(point)
    }

    override fun toWorld(self: LuaHandle.Node, point: Vec3): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.worldMatrix(index).transformPosition(point)
    }

    override fun worldDirection(self: LuaHandle.Node, direction: Vec3): Vec3? {
        val (instance, index) = node(self) ?: return null
        return Quat.fromBasis(instance.worldMatrix(index)).rotate(direction)
    }

    override fun lookAt(self: LuaHandle.Node, point: Vec3): Boolean {
        val (instance, index) = node(self) ?: return false
        // Where the point is from the node's origin, in the space its rotation is in: its parent's.
        val target = instance.parentWorldMatrix(index).invertAffine()?.transformPosition(point) ?: return false
        val forward = target - instance.get(index, Channel.TRANSLATION)
        if (forward.length() < 1e-9) return true
        instance.set(index, Channel.ROTATION, lookRotation(forward.normalized()))
        return true
    }

    override fun rotate(self: LuaHandle.Node, axis: Vec3, degrees: Double): Boolean {
        val length = axis.length()
        if (length == 0.0) throw LuaApiException("bad argument 'axis' (a rotation needs an axis that isn't zero)")
        val (instance, index) = node(self) ?: return false
        val half = degreesToRadians(degrees) / 2
        val k = axis * (sin(half) / length)
        // Pre-multiplied: the turn happens about the parent's axis, after the node's own rotation.
        val turned = Quat(k.x, k.y, k.z, cos(half)) * Quat.fromEuler(instance.get(index, Channel.ROTATION))
        instance.set(index, Channel.ROTATION, turned.normalized().toEuler())
        return true
    }

    override fun isVisible(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.visible(index)
    }

    override fun setVisible(self: LuaHandle.Node, visible: Boolean) {
        val (instance, index) = node(self) ?: return
        instance.setVisible(index, visible)
    }

    override fun isClickable(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.clickable(index)
    }

    override fun setClickable(self: LuaHandle.Node, clickable: Boolean) {
        val (instance, index) = node(self) ?: return
        instance.setClickable(index, clickable)
    }

    override fun displayKind(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        return when (instance.display(index)) {
            is BlockDisplay -> "block"
            is ItemDisplay -> "item"
            is TextDisplay -> "text"
            null -> null
        }
    }

    override fun displayBlock(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        return (instance.display(index) as? BlockDisplay)?.block
    }

    override fun setDisplayBlock(self: LuaHandle.Node, block: String): Boolean {
        val (instance, index) = node(self) ?: return false
        val current = instance.display(index) as? BlockDisplay ?: return false
        val state = validBlock(block) ?: return false
        instance.setDisplay(index, current.copy(block = state.toString()))
        return true
    }

    /** A block state the server knows, with only properties the block has; null otherwise. */
    private fun validBlock(text: String): BlockState? {
        val state = BlockState.parse(text) ?: return null
        val info = session.platform.game.block(state.id) ?: return null
        if (state.properties.any { (name, value) -> info.properties[name]?.contains(value) != true }) return null
        return state
    }

    override fun displayText(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        return (instance.display(index) as? TextDisplay)?.text
    }

    override fun setDisplayText(self: LuaHandle.Node, text: String): Boolean {
        val (instance, index) = node(self) ?: return false
        val current = instance.display(index) as? TextDisplay ?: return false
        instance.setDisplay(index, current.copy(text = text))
        return true
    }

    override fun displayItem(self: LuaHandle.Node): ItemData? {
        val (instance, index) = node(self) ?: return null
        val display = instance.display(index) as? ItemDisplay ?: return null
        return instance.look(index).item ?: ItemData(ItemDef(display.item))
    }

    override fun setDisplayItem(self: LuaHandle.Node, item: ItemData): Boolean {
        val (instance, index) = node(self) ?: return false
        val current = instance.display(index) as? ItemDisplay ?: return false
        // The content names the kind (what a respawn builds); the look carries the whole item over it.
        instance.setDisplay(index, current.copy(item = item.def.kind))
        instance.setLook(index, instance.look(index).copy(item = item.copy(def = item.def.copy(count = null))))
        return true
    }

    override fun displayTextStyle(self: LuaHandle.Node): TextStyle? {
        val (instance, index) = node(self) ?: return null
        val text = instance.display(index) as? TextDisplay ?: return null
        return TextStyle(
            background = text.background,
            opacity = (instance.look(index).textOpacity ?: OPAQUE).toLong(),
            shadow = text.shadow ?: false,
            alignment = (text.alignment ?: TextAlignment.CENTER).name.lowercase(),
            lineWidth = (text.lineWidth ?: TextDisplay.DEFAULT_LINE_WIDTH).toLong(),
            seeThrough = text.seeThrough ?: false
        )
    }

    override fun setDisplayTextStyle(self: LuaHandle.Node, style: TextStyle): Boolean {
        val background = style.background?.also {
            if (!ARGB.matches(it)) throw LuaApiException("bad field 'style.background' (#AARRGGBB expected, got \"$it\")")
        }
        val opacity = style.opacity?.also {
            if (it !in 0..OPAQUE) throw LuaApiException("bad field 'style.opacity' (0 to 255 expected, got $it)")
        }
        val alignment = style.alignment?.let { name ->
            TextAlignment.entries.firstOrNull { it.name.lowercase() == name }
                ?: throw LuaApiException("bad field 'style.alignment' (\"center\", \"left\" or \"right\" expected, got \"$name\")")
        }
        val lineWidth = style.lineWidth?.also {
            if (it < 1 || it > Int.MAX_VALUE) throw LuaApiException("bad field 'style.line_width' (at least 1 expected, got $it)")
        }
        val (instance, index) = node(self) ?: return false
        val current = instance.display(index) as? TextDisplay ?: return false
        instance.setDisplay(
            index,
            current.copy(
                background = background,
                shadow = style.shadow,
                alignment = alignment,
                lineWidth = lineWidth?.toInt(),
                seeThrough = style.seeThrough
            )
        )
        instance.setLook(index, instance.look(index).copy(textOpacity = opacity?.toInt()?.takeIf { it != OPAQUE }))
        return true
    }

    override fun isGlowing(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.display(index) != null && instance.look(index).glowing
    }

    override fun setGlowing(self: LuaHandle.Node, glowing: Boolean): Boolean {
        val (instance, index) = node(self) ?: return false
        if (instance.display(index) == null) return false
        instance.setLook(index, instance.look(index).copy(glowing = glowing))
        return true
    }

    override fun glowColor(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        val display = instance.display(index)
        if (display == null || display is TextDisplay) return null
        return instance.look(index).glowColor?.let(::formatRgb)
    }

    override fun setGlowColor(self: LuaHandle.Node, color: String?): Boolean {
        val rgb = color?.let { parseRgb(it, "color") }
        val (instance, index) = node(self) ?: return false
        val display = instance.display(index)
        // Minecraft draws no glow colour on a text display.
        if (display == null || display is TextDisplay) return false
        instance.setLook(index, instance.look(index).copy(glowColor = rgb))
        return true
    }

    override fun brightness(self: LuaHandle.Node): Brightness? {
        val (instance, index) = node(self) ?: return null
        if (instance.display(index) == null) return null
        return instance.look(index).brightness?.let { Brightness(it.block.toLong(), it.sky.toLong()) }
    }

    override fun setBrightness(self: LuaHandle.Node, brightness: Brightness?): Boolean {
        val levels = brightness?.let {
            fun level(value: Long?, field: String): Int {
                if (value == null) throw LuaApiException("bad field 'brightness.$field' (a light level from 0 to 15 is required)")
                if (value !in 0..15) throw LuaApiException("bad field 'brightness.$field' (0 to 15 expected, got $value)")
                return value.toInt()
            }
            DisplayBrightness(level(it.blockLight, "block_light"), level(it.skyLight, "sky_light"))
        }
        val (instance, index) = node(self) ?: return false
        if (instance.display(index) == null) return false
        instance.setLook(index, instance.look(index).copy(brightness = levels))
        return true
    }

    override fun billboard(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        val display = instance.display(index) ?: return null
        val own = if (display is TextDisplay) display.billboard ?: TextDisplay.DEFAULT_BILLBOARD else Billboard.FIXED
        return (instance.look(index).billboard ?: own).name.lowercase()
    }

    override fun setBillboard(self: LuaHandle.Node, billboard: String): Boolean {
        val value = Billboard.entries.firstOrNull { it.name.lowercase() == billboard }
            ?: throw LuaApiException(
                "bad argument 'billboard' (\"fixed\", \"vertical\", \"horizontal\" or \"center\" expected, got \"$billboard\")"
            )
        val (instance, index) = node(self) ?: return false
        if (instance.display(index) == null) return false
        instance.setLook(index, instance.look(index).copy(billboard = value))
        return true
    }

    override fun interpolationTicks(self: LuaHandle.Node): Long? {
        val (instance, index) = node(self) ?: return null
        return instance.interpolation(index).toLong()
    }

    override fun setInterpolationTicks(self: LuaHandle.Node, ticks: Long): Boolean {
        if (ticks < 0 || ticks > Int.MAX_VALUE) throw LuaApiException("bad argument 'ticks' (at least 0 expected, got $ticks)")
        val (instance, index) = node(self) ?: return false
        instance.setInterpolation(index, ticks.toInt())
        return true
    }

    override fun velocity(self: LuaHandle.Node): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.body(index)?.velocity ?: Vec3.ZERO
    }

    override fun angularVelocity(self: LuaHandle.Node): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.body(index)?.angularVelocity?.let(Physics::toDegrees) ?: Vec3.ZERO
    }

    override fun setVelocity(self: LuaHandle.Node, velocity: Vec3) = motion(self, velocity, spin = false, add = false)

    override fun addVelocity(self: LuaHandle.Node, velocity: Vec3) = motion(self, velocity, spin = false, add = true)

    override fun setAngularVelocity(self: LuaHandle.Node, angularVelocity: Vec3) = motion(self, angularVelocity, spin = true, add = false)

    /** Sets or adds to a body's velocity, or (in degrees) its spin, and wakes it. */
    private fun motion(self: LuaHandle.Node, value: Vec3, spin: Boolean, add: Boolean): Boolean {
        val (instance, index) = node(self) ?: return false
        val body = instance.body(index) ?: return false
        if (spin) {
            val radians = Physics.toRadians(value)
            body.angularVelocity = if (add) body.angularVelocity + radians else radians
        } else {
            body.velocity = if (add) body.velocity + value else value
        }
        body.wake()
        return true
    }

    override fun mass(self: LuaHandle.Node): Double {
        val (instance, index) = node(self) ?: return 0.0
        return session.centities.physics.bodyOf(instance, index)?.mass ?: 0.0
    }

    // Physics runs along the world's axes, relative to the anchor; only the point needs moving.
    override fun applyImpulse(self: LuaHandle.Node, impulse: Vec3): Boolean {
        val (instance, index) = node(self) ?: return false
        return session.centities.physics.impulse(instance, index, impulse, null)
    }

    override fun applyImpulseAt(self: LuaHandle.Node, impulse: Vec3, point: Vec3): Boolean {
        val (instance, index) = node(self) ?: return false
        return session.centities.physics.impulse(instance, index, impulse, instance.fromAnchor(point))
    }

    override fun applyForce(self: LuaHandle.Node, force: Vec3): Boolean {
        val (instance, index) = node(self) ?: return false
        // A force for one tick is the impulse it adds up to over that tick.
        return session.centities.physics.impulse(instance, index, force * Centities.SECONDS_PER_TICK, null)
    }

    override fun hasGravity(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.gravity == true
    }

    override fun setGravity(self: LuaHandle.Node, gravity: Boolean): Boolean = body(self) {
        it.gravity = gravity
        it.wake()
    }

    override fun isKinematic(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.kinematic == true
    }

    override fun setKinematic(self: LuaHandle.Node, kinematic: Boolean): Boolean = body(self) {
        it.kinematic = kinematic
        it.wake()
    }

    override fun isPhysicsEnabled(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.enabled == true
    }

    override fun setPhysicsEnabled(self: LuaHandle.Node, enabled: Boolean): Boolean = body(self) {
        it.enabled = enabled
        it.wake()
    }

    /** Changes a node's body; false for a node without `physics`. */
    private fun body(self: LuaHandle.Node, change: (BodyState) -> Unit): Boolean {
        val (instance, index) = node(self) ?: return false
        val body = instance.body(index) ?: return false
        change(body)
        return true
    }

    override fun isOnGround(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.onGround == true
    }

    override fun isAsleep(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.asleep == true
    }

    override fun wake(self: LuaHandle.Node) {
        val (instance, index) = node(self) ?: return
        instance.body(index)?.wake()
    }
}

/** A text display's opacity when it's fully opaque. */
private const val OPAQUE = 255

private val RGB = Regex("^#[0-9a-fA-F]{6}$")
private val ARGB = Regex("^#[0-9a-fA-F]{8}$")

/** `#RRGGBB` as `0xRRGGBB`; anything else is an error naming [parameter]. */
internal fun parseRgb(text: String, parameter: String): Int {
    if (!RGB.matches(text)) throw LuaApiException("bad argument '$parameter' (#RRGGBB expected, got \"$text\")")
    return text.substring(1).toInt(16)
}

internal fun formatRgb(rgb: Int): String = "#%06x".format(rgb and 0xFFFFFF)

private fun Vec3.length() = sqrt(x * x + y * y + z * z)

private fun Vec3.normalized() = this * (1.0 / length())

private fun Vec3.cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

/**
 * The XYZ euler rotation (degrees) that points +Z along [forward] (a unit
 * vector) with +X level: the basis right = up × forward, up' = forward × right.
 * Straight up or down, where "level" means nothing, +X stays east.
 */
private fun lookRotation(forward: Vec3): Vec3 {
    val flat = Vec3(0.0, 1.0, 0.0).cross(forward)
    val right = if (flat.length() < 1e-9) Vec3(1.0, 0.0, 0.0) else flat.normalized()
    val up = forward.cross(right)
    val basis = Matrix4()
    val v = basis.values
    v[0] = right.x
    v[1] = right.y
    v[2] = right.z
    v[4] = up.x
    v[5] = up.y
    v[6] = up.z
    v[8] = forward.x
    v[9] = forward.y
    v[10] = forward.z
    return Quat.fromBasis(basis).toEuler()
}
