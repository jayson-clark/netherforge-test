package dev.netherforge.plugin.testkit

import dev.netherforge.format.Vec3
import dev.netherforge.plugin.platform.PackOffer
import dev.netherforge.plugin.platform.ParticleOps
import dev.netherforge.plugin.platform.ParticleSpawn
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.ResourcePackOps
import dev.netherforge.plugin.platform.SoundOps
import dev.netherforge.plugin.platform.SoundPlay
import java.util.UUID

// Particles, sounds and resource packs: what the fake server sends players.

class FakeParticles : ParticleOps {
    /** Every batch: its world, its spawns, and who it went to. */
    val sent = mutableListOf<Triple<String, List<ParticleSpawn>, List<PlayerRef>>>()

    override fun spawn(world: String, spawns: List<ParticleSpawn>, viewers: List<PlayerRef>) {
        sent += Triple(world, spawns, viewers)
    }

    /** How many times the runtime said to drop what's kept between spawns. */
    var forgets = 0

    override fun forget() {
        forgets++
    }
}

class FakeSounds(private val platform: FakePlatform) : SoundOps {
    /** Every sound played, as `"<world or player> <sound> <category> <volume> <pitch>"`. */
    val played = mutableListOf<String>()
    val stopped = mutableListOf<String>()

    override fun play(world: String, position: Vec3, sound: SoundPlay): Boolean {
        if (!platform.worlds.exists(world)) return false
        played += "$world ${sound.sound} ${sound.category} ${sound.volume} ${sound.pitch}"
        return true
    }

    override fun playTo(player: UUID, sound: SoundPlay): Boolean {
        val who = platform.players.byId[player] ?: return false
        played += "${who.ref.name} ${sound.sound} ${sound.category} ${sound.volume} ${sound.pitch}"
        return true
    }

    override fun stop(player: UUID, sound: String?): Boolean {
        val who = platform.players.byId[player] ?: return false
        stopped += "${who.ref.name} ${sound ?: "everything"}"
        return true
    }
}

class FakePacks(private val platform: FakePlatform) : ResourcePackOps {
    val sent = mutableListOf<Pair<UUID, PackOffer>>()

    /** Offers [pack]; a player whose game answers ([FakePlayer.packAnswer]) is heard answering. */
    override fun send(player: UUID, pack: PackOffer): Boolean {
        val who = platform.players.byId[player] ?: return false
        sent += player to pack
        who.packAnswer?.let { platform.events?.resourcePackStatus(player, pack.id, it) }
        return true
    }
}
