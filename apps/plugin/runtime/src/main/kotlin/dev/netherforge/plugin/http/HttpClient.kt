package dev.netherforge.plugin.http

import dev.netherforge.plugin.HttpConfig
import dev.netherforge.plugin.session.RuntimeService

/**
 * The session's one [HttpFetcher], made when the first request needs it and
 * closed when the session stops, so a reload (a new session) leaves none of
 * OkHttp's threads or pooled connections behind.
 */
class HttpClient(private val limits: () -> HttpConfig) : RuntimeService {
    override val name = "http"

    private var made: HttpFetcher? = null

    /** The fetcher; made on first use, with the limits as they are then. */
    @get:Synchronized
    val fetcher: HttpFetcher get() = made ?: HttpFetcher(limits()).also { made = it }

    @Synchronized
    override fun stop() {
        made?.close()
        made = null
    }
}
