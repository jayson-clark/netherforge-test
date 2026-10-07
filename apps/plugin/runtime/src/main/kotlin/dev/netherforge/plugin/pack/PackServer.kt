package dev.netherforge.plugin.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The plugin's own HTTP server for the resource pack: the JDK's, on its own
 * daemon threads, serving `GET /<sha1>.zip` and nothing else.
 *
 * It keeps the current build and the one before it, so a player who started
 * downloading just before a rebuild still gets their bytes. The hash in the
 * path means a cache in between can never hand a client stale content under
 * a current name.
 */
class PackServer private constructor(private val server: HttpServer, private val executor: ExecutorService) {
    @Volatile
    private var packs: List<Pair<String, ByteArray>> = emptyList()

    val port: Int get() = server.address.port

    /** The host to put in a URL: the bound address, or loopback when bound to every interface. */
    val host: String get() = server.address.address.let { if (it.isAnyLocalAddress) "127.0.0.1" else it.hostAddress }

    fun serve(sha1: String, bytes: ByteArray) {
        packs = listOf(sha1 to bytes) + packs.filter { it.first != sha1 }.take(1)
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val name = exchange.requestURI.path.removePrefix("/").removeSuffix(".zip")
            val bytes = packs.firstOrNull { it.first == name }?.second
            val method = exchange.requestMethod
            when {
                method != "GET" && method != "HEAD" -> exchange.sendResponseHeaders(405, -1)
                bytes == null -> exchange.sendResponseHeaders(404, -1)
                else -> {
                    exchange.responseHeaders.add("Content-Type", "application/zip")
                    if (method == "HEAD") {
                        exchange.responseHeaders.add("Content-Length", bytes.size.toString())
                        exchange.sendResponseHeaders(200, -1)
                    } else {
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                }
            }
        }
    }

    companion object {
        fun start(bind: String, port: Int): PackServer {
            val server = HttpServer.create(InetSocketAddress(bind, port), 0)
            val executor = Executors.newFixedThreadPool(2) { task ->
                Thread(task, "NetherForge resource pack server").apply { isDaemon = true }
            }
            server.executor = executor
            val pack = PackServer(server, executor)
            server.createContext("/") { pack.handle(it) }
            server.start()
            return pack
        }
    }
}
