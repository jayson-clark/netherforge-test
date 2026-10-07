package dev.netherforge.plugin.centity

import dev.netherforge.format.Vec3
import dev.netherforge.format.centity.Channel
import dev.netherforge.format.centity.CompiledAnimation
import dev.netherforge.format.centity.LoopMode
import dev.netherforge.format.centity.Transform

/**
 * The clips playing on one instance, and what they do to its pose.
 *
 * A clip owns the channels its tracks drive while it plays; everything else
 * stays at the rest pose (the authored transform, or what a script last set).
 * Clips are applied in the order they were started, so the newest wins where
 * two drive the same channel. When a `once` clip ends its channels fall back
 * to rest; a `hold` clip keeps its last pose until stopped; a `loop` clip
 * never ends.
 *
 * Each play can run at its own speed, start part way in, override the
 * clip's loop mode, and blend in: for its first ticks its channels ease from
 * the pose shown when it started into the clip, instead of jumping.
 */
class AnimationPlayer {
    private class Playing(val clip: CompiledAnimation, val loop: LoopMode, var speed: Double, val blend: Blend?) {
        /** Seconds into the clip, as authored (speed already applied). */
        var time = 0.0
        var ended = false
        var paused = false
    }

    /** Easing in from [from] (the shown pose when the clip started) over [seconds]. */
    private class Blend(val from: Array<Transform>, val seconds: Double) {
        var elapsed = 0.0
        val weight: Double get() = if (seconds <= 0.0) 1.0 else (elapsed / seconds).coerceIn(0.0, 1.0)
    }

    private val playing = LinkedHashMap<String, Playing>()

    val active: Boolean get() = playing.isNotEmpty()

    /**
     * Starts [clip], restarting it if it was already playing: at [speed]
     * times its authored rate, [from] seconds in, under [loop] (its own mode
     * when null), easing from [pose] (the pose shown now) over
     * [blendSeconds].
     */
    fun play(
        clip: CompiledAnimation,
        speed: Double = 1.0,
        from: Double = 0.0,
        loop: LoopMode? = null,
        blendSeconds: Double = 0.0,
        pose: Array<Transform>? = null
    ) {
        playing.remove(clip.name)
        val blend = if (blendSeconds > 0.0 && pose != null) Blend(pose.copyOf(), blendSeconds) else null
        playing[clip.name] = Playing(clip, loop ?: clip.loop, speed, blend).also { it.time = from }
    }

    fun stop(name: String): Boolean = playing.remove(name) != null

    fun stopAll() = playing.clear()

    fun isPlaying(name: String): Boolean = name in playing

    fun names(): List<String> = playing.keys.toList()

    /** Holds a clip where it is. False when it isn't playing. */
    fun pause(name: String): Boolean {
        val entry = playing[name] ?: return false
        entry.paused = true
        return true
    }

    fun resume(name: String): Boolean {
        val entry = playing[name] ?: return false
        entry.paused = false
        return true
    }

    /** Where a playing clip is, in seconds within its length; null when it isn't playing. */
    fun position(name: String): Double? = playing[name]?.let { it.clip.resolve(it.time, it.loop).time }

    /** Moves a playing clip to [seconds] in. A hold clip that had ended plays on from there. False when it isn't playing. */
    fun seek(name: String, seconds: Double): Boolean {
        val entry = playing[name] ?: return false
        entry.time = seconds
        entry.ended = false
        return true
    }

    fun speed(name: String): Double? = playing[name]?.speed

    fun setSpeed(name: String, speed: Double): Boolean {
        val entry = playing[name] ?: return false
        entry.speed = speed
        return true
    }

    /**
     * Moves every clip on by [seconds] and returns the ones that reached
     * their end on this step. `once` clips are dropped as they end. A
     * blend's easing counts in real time, whatever the clip's speed or
     * whether it's paused.
     */
    fun advance(seconds: Double): List<String> {
        val ended = mutableListOf<String>()
        val iterator = playing.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.blend?.let { it.elapsed += seconds }
            if (entry.ended || entry.paused) continue
            entry.time += seconds * entry.speed
            if (entry.clip.resolve(entry.time, entry.loop).ended) {
                ended += entry.clip.name
                if (entry.loop == LoopMode.ONCE) iterator.remove() else entry.ended = true
            }
        }
        return ended
    }

    /** Writes every playing clip's channels over [pose], which starts as a copy of the rest pose. */
    fun apply(pose: Array<Transform>) {
        for (entry in playing.values) {
            val at = entry.clip.resolve(entry.time, entry.loop).time
            val weight = entry.blend?.weight ?: 1.0
            for (track in entry.clip.tracks) {
                if (track.nodeIndex !in pose.indices) continue
                var value = track.sample(at)
                if (weight < 1.0) {
                    val from = entry.blend!!.from.getOrNull(track.nodeIndex)?.channel(track.channel) ?: track.channel.identity
                    value = blend(track.channel, from, value, weight)
                }
                val current = pose[track.nodeIndex]
                pose[track.nodeIndex] = when (track.channel) {
                    Channel.TRANSLATION -> current.copy(translation = value)
                    Channel.ROTATION -> current.copy(rotation = value)
                    Channel.SCALE -> current.copy(scale = value)
                }
            }
        }
    }

    private fun Transform.channel(channel: Channel): Vec3 = when (channel) {
        Channel.TRANSLATION -> translationOrDefault
        Channel.ROTATION -> rotationOrDefault
        Channel.SCALE -> scaleOrDefault
    }

    /** From [from] toward [to] by [t]; rotations the short way round each axis, as clips interpolate them. */
    private fun blend(channel: Channel, from: Vec3, to: Vec3, t: Double): Vec3 {
        fun step(a: Double, b: Double): Double {
            if (channel != Channel.ROTATION) return a + (b - a) * t
            var delta = (b - a) % 360.0
            if (delta > 180.0) delta -= 360.0
            if (delta <= -180.0) delta += 360.0
            return a + delta * t
        }
        return Vec3(step(from.x, to.x), step(from.y, to.y), step(from.z, to.z))
    }
}
