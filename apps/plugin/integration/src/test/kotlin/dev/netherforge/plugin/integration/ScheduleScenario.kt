package dev.netherforge.plugin.integration

import dev.netherforge.plugin.integration.support.Scenario
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * `nf.schedule` on a real server, in the shipped jar (cron-utils and all) and the real clock: the
 * schedules are made with their next runs ahead, and a cron schedule for every minute runs on a tick
 * of the main thread within the minute. The rules themselves (zones, daylight saving, catching up)
 * are tested against a clock the test moves, in the runtime's `ScheduleTest`.
 */
class ScheduleScenario : Scenario("schedules") {
    @Test
    @Order(1)
    fun `daily, weekly and cron schedules are made with a next run ahead`() {
        editor.logged("scheduled", "true", "true", "true")
    }

    @Test
    @Order(2)
    fun `a cron schedule runs on the server's tick`() {
        editor.logged("minute", seconds = 90)
    }
}
