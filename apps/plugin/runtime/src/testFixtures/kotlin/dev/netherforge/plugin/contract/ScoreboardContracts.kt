package dev.netherforge.plugin.contract

import dev.netherforge.plugin.platform.BossBarLook
import dev.netherforge.plugin.platform.BossBarOps
import dev.netherforge.plugin.platform.PlayerListOps
import dev.netherforge.plugin.platform.SidebarOps
import dev.netherforge.plugin.platform.TeamLook
import dev.netherforge.plugin.platform.TeamOps
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BossBarOps]: bars by the runtime's ids, shown to players online. */
abstract class BossBarOpsContract : PlatformContract() {
    private val bars: BossBarOps get() = platform.bossBars

    @Test
    fun `a bar shows to players online, and only once it exists`() {
        val player = join()
        main {
            val id = 9001
            afterwards { bars.remove(id) }
            assertFalse(bars.show(id, player.uuid), "no such bar yet")
            bars.create(id, BossBarLook("<red>Boss", 0.5, "red", "notched_6"))
            assertTrue(bars.show(id, player.uuid))
            assertFalse(bars.show(id, UUID.randomUUID()))
            bars.update(id, BossBarLook("<blue>Boss", 1.0, "blue", "progress"))
            bars.hide(id, player.uuid)
            bars.hide(id, UUID.randomUUID())
            bars.remove(id)
            assertFalse(bars.show(id, player.uuid), "removed")
            bars.update(id, BossBarLook("gone", 0.0, "white", "progress"))
            bars.remove(id)
        }
    }
}

/** [SidebarOps]: one sidebar per player online. */
abstract class SidebarOpsContract : PlatformContract() {
    private val sidebars: SidebarOps get() = platform.sidebars

    @Test
    fun `a sidebar shows and hides for players online`() {
        val player = join()
        val offline = UUID.randomUUID()
        main {
            afterwards { sidebars.hide(player.uuid) }
            assertTrue(sidebars.show(player.uuid, "<gold>Title", listOf("one", "<red>two")))
            assertTrue(sidebars.show(player.uuid, "<gold>Title", (1..15).map { "line $it" }), "updated")
            assertTrue(sidebars.hide(player.uuid))
            assertFalse(sidebars.show(offline, "x", emptyList()))
            assertFalse(sidebars.hide(offline))
        }
    }
}

/** [TeamOps]: the main scoreboard's teams and the line under name tags. */
abstract class TeamOpsContract : PlatformContract() {
    private val teams: TeamOps get() = platform.teams
    private val red = "nf.contract_red"
    private val blue = "nf.contract_blue"

    private fun made(name: String, look: TeamLook = TeamLook(name)) {
        assertTrue(teams.create(name, look))
        afterwards { teams.remove(name) }
    }

    @Test
    fun `a team is made once, listed, changed and taken away`() {
        main {
            made(red, TeamLook("<red>Red", prefix = "[R] ", color = "red", friendlyFire = false, nametags = "hide_for_other_teams"))
            assertFalse(teams.create(red, TeamLook("again")), "already there")
            assertTrue(red in teams.names())
            assertEquals(emptySet(), teams.entries(red))
            assertTrue(teams.update(red, TeamLook("<red>Reds", collision = "never")))
            assertTrue(teams.remove(red))
            assertFalse(red in teams.names())
            assertFalse(teams.remove(red))
            assertFalse(teams.update(red, TeamLook("x")))
            assertNull(teams.entries(red))
        }
    }

    @Test
    fun `an entry is in one team at a time`() {
        val pig = UUID.randomUUID().toString()
        main {
            made(red)
            made(blue)
            assertTrue(teams.addEntry(red, "Alex"))
            assertTrue(teams.addEntry(red, pig))
            assertEquals(setOf("Alex", pig), teams.entries(red))
            assertEquals(red, teams.teamOf("Alex"))
            assertTrue(teams.addEntry(blue, "Alex"))
            assertEquals(blue, teams.teamOf("Alex"))
            assertEquals(setOf(pig), teams.entries(red), "moved out")
            assertTrue(teams.removeEntry(blue, "Alex"))
            assertFalse(teams.removeEntry(blue, "Alex"))
            assertNull(teams.teamOf("Alex"))
            assertFalse(teams.addEntry("nf.contract_none", "Alex"))
            assertFalse(teams.removeEntry("nf.contract_none", "Alex"))
        }
    }

    @Test
    fun `lines under name tags come and go`() {
        val player = join()
        main {
            afterwards { teams.clearBelowNames() }
            teams.setBelowName(player.name, "<red>10 hp")
            teams.setBelowName("Alex", "<blue>away")
            teams.setBelowName(player.name, null)
            teams.clearBelowNames()
            teams.setBelowName("Alex", null)
        }
    }
}

/** [PlayerListOps]: each player's entry in the player list, and whose list they're in. */
abstract class PlayerListOpsContract : PlatformContract() {
    private val list: PlayerListOps get() = platform.playerList

    @Test
    fun `an entry's name and place read back as set`() {
        val player = join()
        val offline = UUID.randomUUID()
        main {
            assertEquals(player.name, list.name(player.uuid))
            assertTrue(list.setName(player.uuid, "<gold>Gold"))
            assertEquals("<gold>Gold", list.name(player.uuid))
            assertTrue(list.setName(player.uuid, null))
            assertEquals(player.name, list.name(player.uuid), "back to their name")
            assertEquals(0, list.order(player.uuid))
            assertTrue(list.setOrder(player.uuid, 5))
            assertEquals(5, list.order(player.uuid))
            assertNull(list.name(offline))
            assertFalse(list.setName(offline, "x"))
            assertNull(list.order(offline))
            assertFalse(list.setOrder(offline, 1))
        }
    }

    @Test
    fun `one player can be out of another's list`() {
        val viewer = join()
        val other = join(at(2))
        main {
            assertEquals(true, list.isListed(viewer.uuid, other.uuid))
            assertTrue(list.setListed(viewer.uuid, other.uuid, false))
            assertEquals(false, list.isListed(viewer.uuid, other.uuid))
            assertEquals(true, list.isListed(other.uuid, viewer.uuid), "only that way")
            assertTrue(list.setListed(viewer.uuid, other.uuid, true))
            assertEquals(true, list.isListed(viewer.uuid, other.uuid))
            assertNull(list.isListed(viewer.uuid, UUID.randomUUID()))
            assertFalse(list.setListed(viewer.uuid, UUID.randomUUID(), false))
        }
    }
}
