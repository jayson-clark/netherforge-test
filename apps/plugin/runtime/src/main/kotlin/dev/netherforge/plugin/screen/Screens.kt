package dev.netherforge.plugin.screen

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.Vec3
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Loaded
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.project.ScreenFontKind
import dev.netherforge.format.project.ScreenKind
import dev.netherforge.format.project.VideoKind
import dev.netherforge.format.screen.MapPalette
import dev.netherforge.format.screen.MapScreen
import dev.netherforge.format.screen.ScreenFile
import dev.netherforge.format.screen.ScreenFontFile
import dev.netherforge.format.screen.ScreenViewers
import dev.netherforge.format.screen.VideoFile
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.ScreensConfig
import dev.netherforge.plugin.api.Events
import dev.netherforge.plugin.api.LuaHandle
import dev.netherforge.plugin.api.PlaybackEndEvent
import dev.netherforge.plugin.api.PlaybackFrameEvent
import dev.netherforge.plugin.api.ScreenClickEvent
import dev.netherforge.plugin.api.ScreenHoverEvent
import dev.netherforge.plugin.api.ScreenLeaveEvent
import dev.netherforge.plugin.api.notInProject
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.platform.SoundPlay
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import dev.netherforge.plugin.store.Store
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import kotlin.math.floor

/**
 * One screen: its pixels ([canvas], and a player's own view in [personal]), the maps they're drawn on ([maps],
 * a number each), where it hangs, and who it's drawn for. [id] keys its handle: a project screen's name (`lobby`,
 * `acme:lobby`), or `#<n>` for one a script made, which no name can be.
 */
class ScreenState internal constructor(
    val id: String,
    /** The project's screen it is, or the key a script made it with. */
    val name: String?,
    /** The package it's the project's, or whose script made it. */
    val namespace: String,
    /** The scope that made it; null for a project screen. */
    val owner: Int?,
    var settings: Settings,
    columns: Int,
    rows: Int,
    var maps: IntArray
) {
    /** How it shows: who for, how near, glowing or not, how far a click reaches, its first colour. */
    data class Settings(val viewers: ScreenViewers, val range: Int, val glowing: Boolean, val clickRange: Double, val background: Int)

    var columns = columns
        private set
    var rows = rows
        private set
    var canvas = Canvas(columns, rows).also { it.fill(0, 0, it.width, it.height, settings.background) }
        private set

    /** What its item frames are tagged with: a project screen's and a keyed one's the same every time. */
    val uuid: UUID = UUID.nameUUIDFromBytes("screen:$namespace:${name ?: id}:${owner ?: ""}".toByteArray(StandardCharsets.UTF_8))

    var file: ScreenFile? = null
    var placement: Placement? = null
    var frames: Array<UUID?> = arrayOfNulls(columns * rows)

    /** Whether its frames need hanging again (it moved, or one may have gone with its chunk). */
    var rehang = true

    val shownTo = HashSet<UUID>()
    val hiddenFrom = HashSet<UUID>()
    val viewers = HashMap<UUID, Viewer>()
    val personal = HashMap<UUID, Canvas>()

    /** The video playing on each canvas: the one everyone sees under null, a player's own view under them. */
    val playbacks = HashMap<UUID?, Playback>()

    /** The pixel each player is aiming at, packed `x << 16 | y`, while a `hover` has been heard for them. */
    val hovering = HashMap<UUID, Int>()

    /** Its script's scope, for a project screen with a script. */
    var scope: Scope? = null
    var removed = false

    val tiles: Int get() = columns * rows
    val handle: LuaHandle.Screen get() = LuaHandle.Screen(id, "")

    fun handleFor(player: UUID?): LuaHandle.Screen = LuaHandle.Screen(id, player?.toString() ?: "")

    /** What [player] is shown: their own view, or what everyone sees. */
    fun canvasFor(player: UUID?): Canvas = player?.let { personal[it] } ?: canvas

    /** A new size: everything drawn goes, and every viewer is sent it whole again. */
    internal fun resize(columns: Int, rows: Int, maps: IntArray) {
        this.columns = columns
        this.rows = rows
        this.maps = maps
        canvas = Canvas(columns, rows).also { it.fill(0, 0, it.width, it.height, settings.background) }
        personal.replaceAll { _, _ -> Canvas(columns, rows).also { it.copyFrom(canvas) } }
        frames = arrayOfNulls(columns * rows)
        rehang = true
        for (viewer in viewers.values) viewer.resend(tiles)
    }

    /** The events' path for something [player] did to it: their own view's handlers first, then everyone's. */
    fun <P : dev.netherforge.plugin.api.LuaEvent> path(event: dev.netherforge.plugin.api.EventType<P>, player: UUID) =
        listOfNotNull(personal[player]?.let { event to handleFor(player) }, event to handle)
}

/** A player a screen is drawn for, and what of each of its maps they haven't been sent yet. */
class Viewer(val player: UUID, val world: String?, tiles: Int) {
    var pending: Array<Rect?> = Array(tiles) { Rect.WHOLE }
        private set

    fun add(tile: Int, rect: Rect) {
        pending[tile] = rect.union(pending[tile])
    }

    fun resend(tiles: Int) {
        pending = Array(tiles) { Rect.WHOLE }
    }
}

/**
 * Screens: the project's (`screens/<id>/screen.json`) and those scripts make (`nf.screens.create`), their fonts
 * (`screen_fonts/`) and the videos they play (`videos/`).
 *
 * **Maps.** A screen is maps side by side, each a number the server never made itself
 * ([dev.netherforge.plugin.platform.ScreenOps]): a project screen's numbers, and a keyed script screen's, are kept
 * in the store for good ([Store.ScreenMaps]), from [MAP_BASE] up, so a map item or frame of one shows it again
 * after a restart; a script's screen without a key takes numbers for the session.
 *
 * **Sending.** What a screen shows is the server's to send: every change marks the rectangle of each map it
 * touched ([Canvas]), and once a tick ([TickPhase.UPKEEP], after scripts and videos have drawn) each player it's
 * drawn for ([Viewer]) is sent each map's changed rectangle, at most [ScreensConfig.bytesPerTick] of them a tick;
 * what doesn't fit waits, merged with what changes meanwhile. A player who starts seeing it is sent it whole.
 *
 * **Frames.** A screen that hangs somewhere ([Placement]) is item frames, one a map, tagged [EntityRole.SCREEN],
 * that are never saved with their chunk: they're hung again whenever their chunk is loaded and they aren't there,
 * and taken down when the session ends. A click on one is the screen's ([click]), as is a swing of the arm aimed
 * at it from further away ([swing]).
 *
 * **Videos.** A [Playback] draws a video's frames onto a screen as it goes; its sound is played to the players it's
 * drawn for near it, the whole sound from its start, or a piece at a time after it was paused or moved through.
 */
class Screens(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    private val store: Store.ScreenMaps,
    private val config: ScreensConfig,
    private val readSource: (String) -> String?,
    private val readBytes: (String) -> ByteArray?,
    private val resolve: (String) -> Path?,
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    private val namespace: () -> String
) : RuntimeService {
    override val name get() = "screens"

    private val screens = LinkedHashMap<String, ScreenState>()
    private val frameOwners = HashMap<UUID, Pair<ScreenState, Int>>()
    private var nextScreen = 1L

    private val playbacks = LinkedHashMap<Long, Playback>()
    private var nextPlayback = 1L

    /** The map numbers kept for good, by (namespace, screen key), each by its tile's stable number. */
    private val kept = HashMap<Pair<String, String>, MutableMap<Int, Int>>()
    private var nextMap = MAP_BASE

    private val fontFiles = HashMap<String, ScreenFontFile>()
    private val fonts = HashMap<String, ScreenFont>()
    private var gameFont: ScreenFont? = null

    /**
     * A video as the server runs it: its file, where its frames are (null until converted) and its sounds, and why
     * its frames don't match its file, when they don't ([stale]).
     */
    class Video(val file: VideoFile, val frames: String?, val audio: Playback.Audio?, val stale: Problem? = null)

    private val videos = HashMap<String, Video>()

    val images = ScreenImages(readBytes)

    private var cachedPalette: MapPalette? = null

    /** The game's map colours, read once a session. */
    val palette: MapPalette get() = cachedPalette ?: MapPalette.of(platform.game).also { cachedPalette = it }

    private var ticks = 0L

    /** The tick each player last clicked a screen in: one click a tick, however the server heard of it. */
    private val clicked = HashMap<UUID, Long>()

    // ---- the registry ----------------------------------------------------------------

    fun find(id: String): ScreenState? = screens[id]?.takeIf { !it.removed }

    /** The screen a handle is, and whose own view it is (null for everyone's): null when either is gone. */
    fun resolve(handle: LuaHandle.Screen): Pair<ScreenState, UUID?>? {
        val screen = find(handle.id) ?: return null
        if (handle.player.isEmpty()) return screen to null
        val player = runCatching { UUID.fromString(handle.player) }.getOrNull() ?: return null
        return if (player in screen.personal) screen to player else null
    }

    /** Every project screen, then every script's, that's still there. */
    fun all(): List<ScreenState> = screens.values.filter { !it.removed }

    /** The project's screens' names, for messages. */
    fun projectNames(): List<String> = screens.values.filter { it.owner == null }.map { it.id }.sorted()

    fun fontNames(): List<String> = listOf(ScreenFontFile.DEFAULT) + fontFiles.keys.sorted()

    fun videoNames(): List<String> = videos.keys.sorted()

    fun findPlayback(number: Long): Playback? = playbacks[number]

    override fun define(project: SessionProject) {
        kept.clear()
        for ((key, maps) in store.all()) kept[key] = maps.toMutableMap()
        nextMap = maxOf(MAP_BASE, (kept.values.flatMap { it.values }.maxOrNull() ?: (MAP_BASE - 1)) + 1)
        fontFiles.clear()
        fontFiles += project.running(ScreenFontKind)
        fonts.clear()
        videos.clear()
        for ((name, loaded) in project.everywhere(VideoKind)) videoOf(name, loaded)?.let { videos[name] = it }
        screens.clear()
        for ((name, file) in project.running(ScreenKind)) screens[name] = projectScreen(name, file)
    }

    override fun start() {
        // Who sees each is known before its script draws, so its first frame reaches them on the first tick.
        for (screen in screens.values) view(screen)
        for (screen in screens.values.toList()) startScript(screen)
        for (screen in screens.values.toList()) autoplay(screen)
    }

    override fun stop() {
        for (playback in playbacks.values.toList()) end(playback, EndReason.UNLOADED)
        for (screen in screens.values) {
            screen.scope?.let(scripts::close)
            screen.scope = null
            takeDown(screen)
        }
        screens.clear()
        images.forget()
    }

    override fun tick(phase: TickPhase) {
        when (phase) {
            TickPhase.EFFECTS -> advance()
            TickPhase.UPKEEP -> {
                ticks++
                if (ticks % HANG_EVERY == 0L) for (screen in screens.values) screen.rehang = true
                for (screen in screens.values.toList()) {
                    if (screen.removed) continue
                    if (screen.rehang) hang(screen)
                    if (ticks % VIEW_EVERY == 0L) view(screen)
                }
                hover()
                flush()
            }
            else -> {}
        }
    }

    override fun scopeReleased(scope: Scope) {
        for (playback in playbacks.values.filter { it.owner == scope.id }) end(playback, EndReason.UNLOADED)
        for (screen in screens.values.filter { it.owner == scope.id }) remove(screen)
    }

    override fun playerJoined(player: PlayerRef) {
        for (screen in screens.values) view(screen)
    }

    override fun playerQuit(player: PlayerRef) {
        clicked.remove(player.uuid)
        for (screen in screens.values.toList()) {
            leave(screen, player.uuid)
            screen.viewers.remove(player.uuid)
            if (player.uuid in screen.personal) release(screen, player.uuid)
        }
        for (playback in playbacks.values) silence(playback, player.uuid)
    }

    /** Our frames from a chunk that came back: anything of a screen's it doesn't own is left over, and goes. */
    override fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) {
        for ((entity, tag) in tagged) {
            if (tag.role == EntityRole.SCREEN && entity !in frameOwners) platform.entities.remove(entity)
        }
    }

    override fun entityGone(id: UUID) {
        val (screen, tile) = frameOwners.remove(id) ?: return
        if (screen.frames.getOrNull(tile) == id) {
            screen.frames[tile] = null
            screen.rehang = true
        }
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf(
        "screens" to { scope -> screens.values.count { it.owner == scope.id && !it.removed } },
        "videos" to { scope -> playbacks.values.count { it.owner == scope.id } }
    )

    override fun liveness() = listOf(
        liveness<LuaHandle.Screen> { resolve(it) != null },
        liveness<LuaHandle.Playback> { it.number in playbacks }
    )

    override val reloads: Set<KindSpec<*, *>> get() = setOf(ScreenFontKind, VideoKind, ScreenKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            when (kind) {
                ScreenFontKind -> batch.resource(Resource(ScreenFontKind, id), batch.snapshot.running(ScreenFontKind)[id]) { next ->
                    if (next != null) fontFiles[id] = next else fontFiles.remove(id)
                    fonts.remove(id)
                    0
                }
                VideoKind -> {
                    val loaded = batch.snapshot.everywhere(VideoKind)[id]
                    batch.resource(Resource(VideoKind, id), loaded?.takeIf { it.compiled != null }) { next ->
                        val video = next?.let { videoOf(id, it) }
                        if (video != null) videos[id] = video else videos.remove(id)
                        // Those playing keep the frames they opened; the next play takes the new ones.
                        0
                    }
                }
                ScreenKind -> batch.resource(Resource(ScreenKind, id), batch.snapshot.running(ScreenKind)[id]) { next ->
                    reloadScreen(id, next)
                    0
                }
                else -> {}
            }
        }
        images.forget()
    }

    private fun videoOf(name: String, loaded: Loaded<VideoFile, VideoFile>): Video? {
        val file = loaded.compiled ?: return null
        val place = placeOf(VideoKind, name)
        val frames = place.file(VideoFile.FRAMES).takeIf { VideoFile.FRAMES in loaded.files }
        val (pkg, id) = PackagePaths.split(name)
        val ns = pkg ?: namespace()
        val audio = if (file.audioOrDefault && VideoFile.AUDIO in loaded.files) {
            val pieces = loaded.files.mapNotNull(VideoFile::chunkOf).toSet()
            val chunks = generateSequence(0) { it + 1 }.takeWhile { it in pieces }.map { "$ns:${VideoFile.chunkKey(id, it)}" }.toList()
            Playback.Audio("$ns:${VideoFile.soundKey(id)}", chunks)
        } else {
            null
        }
        return Video(file, frames, audio, frames?.let { staleness(name, file, it) })
    }

    /** Why the frames at [frames] weren't converted for [file] as it is now (or can't be read), or null when they were. */
    private fun staleness(name: String, file: VideoFile, frames: String): Problem? {
        val path = resolve(frames) ?: return null
        val main = placeOf(VideoKind, name).path
        val header = try {
            NfvReader.open(path).use { it.header }
        } catch (e: IOException) {
            return ProblemCodes.VIDEO_STALE.at(main, "Its converted frames can't be read (${e.message}): convert it again")
        }
        val differs = buildList {
            if (header.columns != file.width || header.rows != file.height) add("${header.columns} by ${header.rows} maps")
            if (header.fps != file.fpsOrDefault) add("${header.fps} frames a second")
            if (header.dither != file.ditherOrDefault) add("dithered ${header.dither.name.lowercase()}")
            if (header.fit != file.fitOrDefault) add("fit ${header.fit.name.lowercase()}")
            if (header.audio != file.audioOrDefault) add(if (header.audio) "with sound" else "without sound")
        }
        if (differs.isEmpty()) return null
        return ProblemCodes.VIDEO_STALE.at(
            main,
            "Its frames were converted ${differs.joinToString(", ")}: convert it again to match its file"
        )
    }

    override fun problems(): List<Problem> = videos.values.mapNotNull { it.stale }

    // ---- project screens -------------------------------------------------------------

    private fun projectScreen(name: String, file: ScreenFile): ScreenState {
        val (pkg, bare) = PackagePaths.split(name)
        val ns = pkg ?: namespace()
        val screen =
            ScreenState(name, name, ns, null, settingsOf(file), file.width, file.height, maps(ns, "screen:$bare", file.width, file.height))
        screen.file = file
        screen.placement = file.place?.let { Placement(it.world, it.x, it.y, it.z, it.facing) }
        return screen
    }

    private fun settingsOf(file: ScreenFile) = ScreenState.Settings(
        file.viewersOrDefault,
        file.rangeOrDefault,
        file.glowingOrDefault,
        file.clickRangeOrDefault,
        file.background?.let(::color) ?: MapPalette.TRANSPARENT
    )

    private fun startScript(screen: ScreenState) {
        val script = screen.file?.script ?: return
        val path = placeOf(ScreenKind, screen.id).file(script.file)
        val source = readSource(path) ?: return
        screen.scope = scripts.start(ScopeOwner.ScreenScript(screen.id, path), script, source).first
    }

    /** A project screen's `video`: played from the start, belonging to the screen. */
    private fun autoplay(screen: ScreenState) {
        val video = screen.file?.video ?: return
        // A package's file is read with its references written in full, so this is the video as the server names it.
        val name = video.video.nameIn(namespace()) ?: return
        try {
            if (play(screen, null, name, null, PlayOptions(loop = video.loopOrDefault, volume = video.volumeOrDefault)) == null) {
                log.warn("Screen ${screen.id} can't play video $name: it hasn't been converted, or its frames can't be read")
            }
        } catch (e: LuaApiException) {
            log.warn("Screen ${screen.id} can't play video $name: ${e.message}")
        }
    }

    private fun reloadScreen(id: String, next: ScreenFile?) {
        val previous = screens[id]
        if (previous == null) {
            if (next == null) return
            val screen = projectScreen(id, next)
            screens[id] = screen
            view(screen)
            startScript(screen)
            autoplay(screen)
            return
        }
        previous.scope?.let(scripts::close)
        previous.scope = null
        for (playback in previous.playbacks.values.filter { it.owner == null }.toList()) end(playback, EndReason.UNLOADED)
        if (next == null) {
            remove(previous)
            screens.remove(id)
            return
        }
        previous.file = next
        previous.settings = settingsOf(next)
        if (next.width != previous.columns || next.height != previous.rows) {
            for (playback in previous.playbacks.values.toList()) end(playback, EndReason.REMOVED)
            takeDown(previous)
            previous.resize(
                next.width,
                next.height,
                maps(previous.namespace, "screen:${PackagePaths.split(id).second}", next.width, next.height)
            )
        } else {
            previous.canvas.fill(0, 0, previous.canvas.width, previous.canvas.height, previous.settings.background)
        }
        val placement = next.place?.let { Placement(it.world, it.x, it.y, it.z, it.facing) }
        if (placement != previous.placement) {
            takeDown(previous)
            previous.placement = placement
        }
        previous.rehang = true
        view(previous)
        startScript(previous)
        autoplay(previous)
    }

    // ---- scripts' screens -------------------------------------------------------------

    /** What `nf.screens.create` takes, checked. */
    data class Made(val columns: Int, val rows: Int, val key: String?, val settings: ScreenState.Settings)

    /** Makes a screen for scope [owner] of package [namespace]. A key another screen still there has is an error. */
    fun create(made: Made, owner: Int, namespace: String): ScreenState {
        if (made.key != null &&
            screens.values.any { !it.removed && it.owner != null && it.namespace == namespace && it.name == made.key }
        ) {
            throw LuaApiException("a screen with the key \"${made.key}\" is still there: remove it first, or use another key")
        }
        val maps = maps(namespace, made.key?.let { "key:$it" }, made.columns, made.rows)
        val screen = ScreenState("#${nextScreen++}", made.key, namespace, owner, made.settings, made.columns, made.rows, maps)
        screens[screen.id] = screen
        view(screen)
        return screen
    }

    /** Takes [screen] away: its frames, its videos, everyone's own views of it, every handler on it. */
    fun remove(screen: ScreenState) {
        if (screen.removed) return
        for (playback in screen.playbacks.values.toList()) end(playback, EndReason.REMOVED)
        takeDown(screen)
        for (player in screen.hovering.keys.toList()) leave(screen, player)
        screen.removed = true
        screen.viewers.clear()
        for (player in screen.personal.keys.toList()) scripts.dropTarget(screen.handleFor(player))
        screen.personal.clear()
        scripts.dropTarget(screen.handle)
        if (screen.owner != null) screens.remove(screen.id)
    }

    /** The map numbers of a [columns] by [rows] screen: kept for good under [key] in [namespace], or for now with none. */
    private fun maps(namespace: String, key: String?, columns: Int, rows: Int): IntArray {
        val keep = key?.let { kept.getOrPut(namespace to it) { HashMap() } }
        return IntArray(columns * rows) { tile ->
            val stable = (tile / columns) * MapScreen.MAX_TILES + tile % columns
            keep?.get(stable) ?: nextMap++.also { map ->
                if (keep != null && key != null) {
                    keep[stable] = map
                    store.put(namespace, key, stable, map)
                }
            }
        }
    }

    // ---- colours, fonts and pictures -----------------------------------------------

    /** [text] as a map colour: `#rrggbb`'s nearest, or transparent for `"clear"` and null. */
    fun color(text: String?): Int {
        if (text == null || text == CLEAR) return MapPalette.TRANSPARENT
        val rgb = MapPalette.parse(text) ?: throw LuaApiException("\"$text\" isn't a colour: write #rrggbb, or \"$CLEAR\"")
        return palette.nearest(rgb)
    }

    /** A map colour as a script reads it: `#rrggbb`, or `"clear"`. */
    fun colorName(index: Int): String = palette.rgb(index)?.let { "#%06x".format(it) } ?: CLEAR

    /** The font [name] (as the project names it, or `default`): loaded the first time it's asked for. */
    fun font(name: String): ScreenFont {
        if (name == ScreenFontFile.DEFAULT) return gameFont ?: ScreenFont.of(platform.screens.font()).also { gameFont = it }
        fonts[name]?.let { return it }
        val file = fontFiles[name] ?: throw LuaApiException(notInProject("screen font", name, fontNames()))
        val bytes = file.file?.let { path ->
            readBytes(placeOf(ScreenFontKind, name).file(path)) ?: throw LuaApiException("screen font \"$name\" has no $path")
        }
        val font = try {
            ScreenFont.of(file, bytes)
        } catch (e: Exception) {
            throw LuaApiException("screen font \"$name\" can't be read: ${e.message ?: e.javaClass.simpleName}")
        }
        fonts[name] = font
        return font
    }

    // ---- hanging ----------------------------------------------------------------------

    /** Hangs [screen] at [placement] (null: nowhere), taking down where it hung. */
    fun place(screen: ScreenState, placement: Placement?) {
        takeDown(screen)
        for (player in screen.hovering.keys.toList()) leave(screen, player)
        screen.placement = placement
        screen.rehang = true
        hang(screen)
        view(screen)
    }

    /** Hangs every frame of [screen] that isn't up and whose chunk is loaded. */
    private fun hang(screen: ScreenState) {
        screen.rehang = false
        val placement = screen.placement ?: return
        for (tile in 0 until screen.tiles) {
            val existing = screen.frames[tile]
            if (existing != null && platform.entities.isLoaded(existing)) continue
            val at = placement.locationOf(tile % screen.columns, tile / screen.columns)
            if (!platform.worlds.entitiesLoaded(at)) continue
            existing?.let {
                frameOwners.remove(it)
                platform.entities.remove(it)
            }
            val tag = EntityTag(screen.uuid, "${tile % screen.columns},${tile / screen.columns}", EntityRole.SCREEN)
            val frame = platform.screens.hang(at, placement.facing, screen.maps[tile], screen.settings.glowing, tag)
            screen.frames[tile] = frame
            if (frame != null) frameOwners[frame] = screen to tile
        }
    }

    private fun takeDown(screen: ScreenState) {
        for ((tile, frame) in screen.frames.withIndex()) {
            frame ?: continue
            frameOwners.remove(frame)
            platform.entities.remove(frame)
            screen.frames[tile] = null
        }
    }

    // ---- who sees it --------------------------------------------------------------------

    /** Works out who [screen] is drawn for now: those it's new to are sent it whole, those it isn't any more forgotten. */
    fun view(screen: ScreenState) {
        if (screen.removed) return
        val wanted = HashMap<UUID, String?>()
        val placement = screen.placement
        for (player in platform.players.online()) {
            val uuid = player.uuid
            if (uuid in screen.hiddenFrom) continue
            val at = platform.players.location(uuid) ?: continue
            val near = when {
                uuid in screen.shownTo -> true
                screen.settings.viewers == ScreenViewers.SCRIPT -> false
                placement == null -> true
                else -> at.world == placement.world && within(at.x, at.y, at.z, placement, screen, screen.settings.range.toDouble())
            }
            if (near) wanted[uuid] = at.world
        }
        for (player in screen.viewers.keys.toList()) {
            val viewer = screen.viewers.getValue(player)
            if (player !in wanted || wanted[player] != viewer.world) {
                screen.viewers.remove(player)
                leave(screen, player)
            }
        }
        for ((player, world) in wanted) {
            // Someone new, or back from another world (their game forgets maps it's no longer in), is sent it whole.
            if (player !in screen.viewers) screen.viewers[player] = Viewer(player, world, screen.tiles)
        }
    }

    private fun within(x: Double, y: Double, z: Double, placement: Placement, screen: ScreenState, range: Double): Boolean {
        val (mx, my, mz) = placement.middle(screen.columns, screen.rows)
        val dx = x - mx
        val dy = y - my
        val dz = z - mz
        return dx * dx + dy * dy + dz * dz <= range * range
    }

    fun showTo(screen: ScreenState, player: UUID) {
        screen.hiddenFrom.remove(player)
        screen.shownTo += player
        view(screen)
    }

    fun hideFrom(screen: ScreenState, player: UUID) {
        screen.shownTo.remove(player)
        screen.hiddenFrom += player
        view(screen)
    }

    /** [player]'s own view of [screen], made from what everyone sees now when they have none. */
    fun personal(screen: ScreenState, player: UUID): Canvas = screen.personal.getOrPut(player) {
        // It starts as what everyone sees, which is what they have (or are about to be sent): only what's drawn on it from now on is news.
        Canvas(screen.columns, screen.rows).also {
            it.copyFrom(screen.canvas)
            it.settle()
        }
    }

    /** Ends [player]'s own view of [screen]: they're sent what everyone sees. */
    fun release(screen: ScreenState, player: UUID): Boolean {
        screen.personal.remove(player) ?: return false
        screen.playbacks[player]?.let { end(it, EndReason.REMOVED) }
        screen.viewers[player]?.resend(screen.tiles)
        scripts.dropTarget(screen.handleFor(player))
        return true
    }

    // ---- sending ---------------------------------------------------------------------

    /** Sends every viewer what changed of the screens they see, each up to the tick's budget. */
    private fun flush() {
        val budget = HashMap<UUID, Int>()
        val all = screens.values.filter { !it.removed }
        if (all.isEmpty()) return
        // Each tick starts with another screen, so one big screen can't always have a player's budget first.
        val first = (ticks % all.size).toInt()
        for (i in all.indices) {
            val screen = all[(first + i) % all.size]
            gather(screen.canvas, screen) { it !in screen.personal }
            for ((player, canvas) in screen.personal) gather(canvas, screen) { it == player }
            for (viewer in screen.viewers.values) send(screen, viewer, budget)
        }
    }

    /** Hands each changed map of [canvas] to the viewers of [screen] who see it ([sees]). */
    private fun gather(canvas: Canvas, screen: ScreenState, sees: (UUID) -> Boolean) {
        if (!canvas.changed) return
        for (tile in 0 until screen.tiles) {
            val rect = canvas.take(tile) ?: continue
            for (viewer in screen.viewers.values) if (sees(viewer.player)) viewer.add(tile, rect)
        }
        canvas.settle()
    }

    private fun send(screen: ScreenState, viewer: Viewer, budget: HashMap<UUID, Int>) {
        var left = budget[viewer.player] ?: config.bytesPerTick
        if (left <= 0) return
        val canvas = screen.canvasFor(viewer.player)
        val pending = viewer.pending
        val start = (ticks % pending.size).toInt()
        for (i in pending.indices) {
            val tile = (start + i) % pending.size
            val rect = pending[tile] ?: continue
            val cost = rect.area + PACKET_COST
            if (cost > left) break
            if (!platform.screens.sendMap(
                    viewer.player,
                    screen.maps[tile],
                    rect.left,
                    rect.top,
                    rect.width,
                    rect.height,
                    canvas.region(tile, rect)
                )
            ) {
                break
            }
            pending[tile] = null
            left -= cost
        }
        budget[viewer.player] = left
    }

    // ---- clicks and aim ----------------------------------------------------------------

    /** A click on one of our frames: the screen's, when it's one of theirs. True when the frame is a screen's. */
    fun click(entity: UUID, player: PlayerRef, button: ClickButton, sight: Ray?): Boolean {
        val (screen, tile) = frameOwners[entity] ?: return false
        if (player.uuid !in screen.viewers) return true
        val placement = screen.placement ?: return true
        // Where along their sight they aimed, or the middle of the map when the server didn't say.
        val hit = sight?.let { placement.hit(it, screen.columns, screen.rows, CLICK_REACH) }
        val x = hit?.x ?: ((tile % screen.columns) * Canvas.TILE + Canvas.TILE / 2)
        val y = hit?.y ?: ((tile / screen.columns) * Canvas.TILE + Canvas.TILE / 2)
        clickAt(screen, player.uuid, x, y, button)
        return true
    }

    /**
     * A swing of the arm (or a right click in the air) by [player], aimed at a screen within its click range: its
     * click, and true. False when they aimed at none, so the swing is the world's.
     */
    fun swing(player: PlayerRef, button: ClickButton): Boolean {
        val eye = platform.players.eye(player.uuid) ?: return false
        val (screen, hit) = aim(player.uuid, eye, null) ?: return false
        clickAt(screen, player.uuid, hit.x, hit.y, button)
        return true
    }

    private fun clickAt(screen: ScreenState, player: UUID, x: Int, y: Int, button: ClickButton) {
        if (clicked[player] == ticks) return
        clicked[player] = ticks
        val handle = screen.handleFor(player.takeIf { it in screen.personal })
        val event = ScreenClickEvent(handle, LuaHandle.Player(player.toString()), x.toLong(), y.toLong(), button.luaName)
        scripts.emit(screen.path(Events.SCREEN_CLICK, player), event)
    }

    /**
     * The nearest screen [player]'s [eye] meets that's drawn for them, within [reach] blocks (each screen's click
     * range when null), with nothing solid in front of it.
     */
    fun aim(player: UUID, eye: Ray, reach: Double?): Pair<ScreenState, Placement.Hit>? {
        var best: Pair<ScreenState, Placement.Hit>? = null
        for (screen in screens.values) {
            if (screen.removed || player !in screen.viewers) continue
            val placement = screen.placement ?: continue
            val hit = placement.hit(eye, screen.columns, screen.rows, reach ?: screen.settings.clickRange) ?: continue
            if (best == null || hit.distance < best.second.distance) best = screen to hit
        }
        val found = best ?: return null
        val block = platform.worlds.raycastBlocks(
            eye.world,
            Vec3(eye.x, eye.y, eye.z),
            Vec3(eye.dx, eye.dy, eye.dz),
            found.second.distance,
            false
        )
        if (block != null) {
            val p = block.position
            val d = Math.sqrt((p.x - eye.x) * (p.x - eye.x) + (p.y - eye.y) * (p.y - eye.y) + (p.z - eye.z) * (p.z - eye.z))
            if (d < found.second.distance - OCCLUSION_SLACK) return null
        }
        return found
    }

    /** Each tick: who aims at which pixel of a screen whose `hover` or `leave` someone hears. */
    private fun hover() {
        for (screen in screens.values.toList()) {
            if (screen.removed || screen.placement == null || !hearsAim(screen)) continue
            for (player in screen.viewers.keys.toList()) {
                val eye = platform.players.eye(player)
                val hit = eye?.let { aim(player, it, null) }?.takeIf { it.first === screen }?.second
                val before = screen.hovering[player]
                if (hit == null) {
                    if (before != null) leave(screen, player)
                    continue
                }
                val packed = (hit.x shl 16) or hit.y
                if (before == packed) continue
                screen.hovering[player] = packed
                val handle = screen.handleFor(player.takeIf { it in screen.personal })
                scripts.emit(
                    screen.path(Events.SCREEN_HOVER, player),
                    ScreenHoverEvent(handle, LuaHandle.Player(player.toString()), hit.x.toLong(), hit.y.toLong())
                )
                if (screen.removed) break
            }
        }
    }

    private fun hearsAim(screen: ScreenState): Boolean {
        val handles = listOf(screen.handle) + screen.personal.keys.map(screen::handleFor)
        return handles.any { scripts.listening(it, Events.SCREEN_HOVER) || scripts.listening(it, Events.SCREEN_LEAVE) }
    }

    /** [player] no longer aims at [screen]: its `leave`, when a `hover` was heard for them. */
    private fun leave(screen: ScreenState, player: UUID) {
        screen.hovering.remove(player) ?: return
        val handle = screen.handleFor(player.takeIf { it in screen.personal })
        scripts.emit(screen.path(Events.SCREEN_LEAVE, player), ScreenLeaveEvent(handle, LuaHandle.Player(player.toString())))
    }

    // ---- videos -------------------------------------------------------------------------

    /** What `screen:play` takes, checked. */
    data class PlayOptions(
        val loop: Boolean = false,
        val speed: Double = 1.0,
        val start: Double = 0.0,
        val volume: Double = 1.0,
        val x: Int = 0,
        val y: Int = 0,
        val audioRange: Double = DEFAULT_AUDIO_RANGE
    )

    /**
     * Plays [video] (as the project names it) on [screen] ([viewer]'s own view, or everyone's), for [owner]. Null
     * when it hasn't been converted, or its frames can't be read; a video the project doesn't have is an error.
     */
    fun play(screen: ScreenState, viewer: UUID?, video: String, owner: Int?, options: PlayOptions): Playback? {
        val found = videos[video] ?: throw LuaApiException(notInProject("video", video, videoNames()))
        val path = found.frames?.let(resolve) ?: return null
        val reader = try {
            NfvReader.open(path)
        } catch (e: IOException) {
            log.warn("Video $video can't be played: ${e.message}")
            return null
        }
        val playback = Playback(
            nextPlayback++,
            video,
            screen,
            viewer,
            reader,
            owner,
            options.x,
            options.y,
            options.loop,
            options.speed.coerceIn(MIN_SPEED, MAX_SPEED),
            options.volume.coerceIn(0.0, 1.0),
            options.audioRange,
            found.audio
        )
        playback.position = options.start.coerceIn(0.0, reader.header.length)
        playback.fromStart = playback.position == 0.0
        val previous = screen.playbacks[viewer]
        screen.playbacks[viewer] = playback
        playbacks[playback.number] = playback
        previous?.let { end(it, EndReason.REPLACED) }
        try {
            playback.drawTo(playback.frameAt(playback.position), screen.canvasFor(viewer))
        } catch (e: IOException) {
            log.warn("Video $video can't be played: ${e.message}")
            end(playback, EndReason.STOPPED)
            return null
        }
        if (playback.fromStart) startWhole(playback)
        return playback
    }

    /** Each tick: every video moves on, draws the frames it passed, and plays its sound. */
    private fun advance() {
        for (playback in playbacks.values.toList()) {
            if (playback.ended) continue
            try {
                step(playback)
            } catch (e: Exception) {
                log.error("Video ${playback.video} failed, so it was stopped", e)
                end(playback, EndReason.STOPPED)
            }
        }
    }

    private fun step(playback: Playback) {
        if (playback.paused) return
        val screen = playback.screen
        if (screen.removed || (playback.viewer != null && playback.viewer !in screen.personal)) return end(playback, EndReason.REMOVED)
        val before = playback.position
        playback.position += playback.speed / TICKS_PER_SECOND
        val header = playback.header
        if (playback.frameAt(playback.position) >= header.frames) {
            if (!playback.loop) {
                playback.drawTo(header.frames - 1, screen.canvasFor(playback.viewer))
                return end(playback, EndReason.FINISHED)
            }
            playback.position = (playback.position - header.length).coerceIn(0.0, header.length)
            silence(playback)
            playback.fromStart = playback.speed == 1.0
            if (playback.fromStart) startWhole(playback)
            sound(playback, playback.position, playback.position)
        } else {
            sound(playback, before, playback.position)
        }
        val target = playback.frameAt(playback.position)
        if (playback.drawTo(target, screen.canvasFor(playback.viewer)) > 0 && scripts.listening(playback.handle, Events.PLAYBACK_FRAME)) {
            scripts.emit(
                Events.PLAYBACK_FRAME,
                playback.handle,
                PlaybackFrameEvent(
                    playback.handle,
                    target.toLong(),
                    target.toDouble() / header.fps
                )
            )
        }
    }

    fun pause(playback: Playback): Boolean {
        if (playback.ended || playback.paused) return false
        playback.paused = true
        silence(playback)
        playback.fromStart = false
        return true
    }

    fun resume(playback: Playback): Boolean {
        if (playback.ended || !playback.paused) return false
        playback.paused = false
        return true
    }

    fun seek(playback: Playback, seconds: Double): Boolean {
        if (playback.ended) return false
        playback.seek(seconds)
        silence(playback)
        playback.drawTo(playback.frameAt(playback.position), playback.screen.canvasFor(playback.viewer))
        return true
    }

    fun setSpeed(playback: Playback, speed: Double): Boolean {
        if (playback.ended) return false
        val next = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (next != playback.speed) {
            playback.speed = next
            silence(playback)
            playback.fromStart = false
        }
        return true
    }

    fun setVolume(playback: Playback, volume: Double): Boolean {
        if (playback.ended) return false
        playback.volume = volume.coerceIn(0.0, 1.0)
        silence(playback)
        playback.fromStart = false
        return true
    }

    /** Ends [playback], its `end` heard while it still answers; the screen keeps its last frame. */
    fun end(playback: Playback, reason: EndReason) {
        if (playback.ended) return
        playback.ended = true
        silence(playback)
        val screen = playback.screen
        if (screen.playbacks[playback.viewer] === playback) screen.playbacks.remove(playback.viewer)
        runCatching { playback.reader.close() }
        try {
            scripts.emit(Events.PLAYBACK_END, playback.handle, PlaybackEndEvent(playback.handle, reason.luaName))
        } finally {
            playbacks.remove(playback.number)
            scripts.dropTarget(playback.handle)
        }
    }

    /** Who hears [playback] now: who it's drawn for, near where its screen hangs. */
    private fun listeners(playback: Playback): Set<UUID> {
        val screen = playback.screen
        val placement = screen.placement
        return screen.viewers.keys.filter { player ->
            (playback.viewer == null || playback.viewer == player) &&
                (
                    placement == null ||
                        platform.players.location(player)?.let { within(it.x, it.y, it.z, placement, screen, playback.audioRange) } == true
                    )
        }.toSet()
    }

    private fun audible(playback: Playback) = playback.audio != null && !playback.paused && playback.speed == 1.0 && playback.volume > 0.0

    /** Plays the whole sound from its start to everyone who hears it now. */
    private fun startWhole(playback: Playback) {
        val audio = playback.audio ?: return
        if (!audible(playback)) return
        for (player in listeners(playback)) {
            if (platform.sounds.playTo(player, SoundPlay(audio.whole, SOUND_CATEGORY, playback.volume, 1.0))) playback.wholeFor += player
        }
    }

    /** Its sound as it moved from [before] to [now]: those out of earshot stopped, and a new piece started at each boundary. */
    private fun sound(playback: Playback, before: Double, now: Double) {
        val audio = playback.audio ?: return
        if (!audible(playback)) return silence(playback)
        val hearing = listeners(playback)
        for (player in (playback.wholeFor + playback.pieceFor.keys) - hearing) silence(playback, player)
        val piece = floor(now / Playback.Audio.CHUNK + 1e-9).toInt()
        val crossed = piece != floor(before / Playback.Audio.CHUNK + 1e-9).toInt() || (before == now && now == 0.0)
        if (!crossed || piece !in audio.chunks.indices) return
        for (player in hearing) {
            if (player in playback.wholeFor) continue
            playback.pieceFor[player]?.let { platform.sounds.stop(player, audio.chunks.getOrNull(it)) }
            if (platform.sounds.playTo(player, SoundPlay(audio.chunks[piece], SOUND_CATEGORY, playback.volume, 1.0))) {
                playback.pieceFor[player] = piece
            }
        }
    }

    /** Stops [playback]'s sound for [player], or for everyone. */
    private fun silence(playback: Playback, player: UUID? = null) {
        val audio = playback.audio ?: return
        val who = player?.let(::setOf) ?: (playback.wholeFor + playback.pieceFor.keys)
        for (p in who) {
            if (playback.wholeFor.remove(p)) platform.sounds.stop(p, audio.whole)
            playback.pieceFor.remove(p)?.let { audio.chunks.getOrNull(it)?.let { name -> platform.sounds.stop(p, name) } }
        }
    }

    /** Why a video ended, as `event.reason` says it. */
    enum class EndReason(val luaName: String) {
        FINISHED("finished"),
        STOPPED("stopped"),
        REPLACED("replaced"),
        REMOVED("removed"),
        UNLOADED("unloaded")
    }

    companion object {
        /** The first map number screens take: far past any the server will make itself. */
        const val MAP_BASE = 1_000_000_000

        const val CLEAR = "clear"

        /** Ticks between checks that every frame is up (one goes with its chunk). */
        const val HANG_EVERY = 20L

        /** Ticks between working out who's near each screen. */
        const val VIEW_EVERY = 10L

        /** What a map packet costs besides its pixels, against a player's budget. */
        const val PACKET_COST = 32

        /** How far a click on a frame is looked for along the player's sight: past any reach the game allows. */
        const val CLICK_REACH = 16.0

        /** How much nearer than a screen a block must be to be in front of it (the wall it hangs on is just behind). */
        const val OCCLUSION_SLACK = 0.05

        const val DEFAULT_AUDIO_RANGE = 32.0
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 4.0
        private const val TICKS_PER_SECOND = 20.0
        private const val SOUND_CATEGORY = "record"
    }
}
